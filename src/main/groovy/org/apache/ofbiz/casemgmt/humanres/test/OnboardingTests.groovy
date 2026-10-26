/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.casemgmt.humanres.test

import java.sql.Timestamp

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.casemgmt.form.CaseFormMetadataHelper
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Employee onboarding case tests.
 */
class OnboardingTests extends OFBizTestCase {

    private static final String FORM_LOCATION = 'component://casemgmt-humanres/widget/OnboardingForms.xml'

    OnboardingTests(String name) {
        super(name)
    }

    private GenericValue system() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private GenericValue currentTask(String caseId, long stageSeq) {
        return from('CaseTask')
                .where('caseId', caseId, 'stageSeq', stageSeq)
                .orderBy('stepSeq')
                .queryList()
                .find { GenericValue task ->
                    !['TASK_COMPLETED', 'TASK_CANCELLED', 'TASK_SKIPPED'].contains(task.statusId)
                }
    }

    private Map submitTask(GenericValue task, Map formValues) {
        Map values = new LinkedHashMap(formValues)
        values.outcomeEnumId = 'CASE_OUT_COMPLETE'
        Map result = dispatcher.runSync('submitCaseTaskForm', [
                userLogin : system(),
                caseTaskId: task.caseTaskId,
                formValues: values
        ])
        ok(result)
        return result
    }

    void testOnboardingFormDefinitions() {
        assert CaseFormMetadataHelper.validateFormDefinition(
                delegator, dispatcher, FORM_LOCATION, 'OnboardingStartForm', 'StartCaseSubmit') == null
        assert CaseFormMetadataHelper.validateFormDefinition(
                delegator, dispatcher, FORM_LOCATION, 'OnboardingEmployeeForm', 'TaskFormSubmit') == null
        assert CaseFormMetadataHelper.validateFormDefinition(
                delegator, dispatcher, FORM_LOCATION, 'OnboardingTrainingForm', 'TaskFormSubmit') == null
        assert CaseFormMetadataHelper.validateFormDefinition(
                delegator, dispatcher, FORM_LOCATION, 'OnboardingEquipmentForm', 'TaskFormSubmit') == null
        assert CaseFormMetadataHelper.validateFormDefinition(
                delegator, dispatcher, FORM_LOCATION, 'OnboardingAccountForm', 'TaskFormSubmit') == null
        assert CaseFormMetadataHelper.validateFormDefinition(
                delegator, dispatcher, FORM_LOCATION, 'OnboardingCompletionForm', 'TaskFormSubmit') == null
    }

    void testOnboardingEndToEnd() {
        String stamp = System.currentTimeMillis().toString()
        Timestamp now = UtilDateTime.nowTimestamp()
        String employeeEmail = "onboard-${stamp}@example.com"

        Map start = dispatcher.runSync('submitStartCase', [
                userLogin : system(),
                caseTypeId: 'ONBOARDING',
                formValues: [
                        firstName      : 'Taro',
                        middleName     : 'Onboarding',
                        lastName       : "Employee${stamp}".toString(),
                        personalEmail  : employeeEmail,
                        targetStartDate: '2026-11-01',
                        description    : 'Onboarding end-to-end test'
                ]
        ])
        ok(start)
        String caseId = start.caseId
        assert caseId

        GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
        assert caseValue
        String employeePartyId = caseValue.customerPartyId
        assert employeePartyId
        assert from('Person').where('partyId', employeePartyId).queryOne()
        GenericValue caseContext = from('CaseContext')
                .where('caseId', caseId, 'entityName', 'Party').queryOne()
        assert caseContext

        GenericValue employeeTask = currentTask(caseId, 1)
        assert employeeTask
        Map employeeResult = submitTask(employeeTask, [
                departmentPartyId : 'TEST_HR_DEPT',
                managerPartyId    : 'TEST_HR_MANAGER',
                jobTitle          : 'Software Engineer',
                employmentFromDate: now,
                notes             : 'Employment registration completed'
        ])
        assert employeeResult.formServiceName == 'onboardingRegisterEmployee'
        assert from('PartyRole').where('partyId', employeePartyId,
                'roleTypeId', 'EMPLOYEE').queryOne()
        assert from('Employment').where('partyIdTo', employeePartyId,
                'roleTypeIdTo', 'EMPLOYEE').queryOne()
        assert from('PartyRelationship').where('partyIdFrom', 'TEST_HR_MANAGER',
                'partyIdTo', employeePartyId).queryOne()
        assert from('PartyAttribute').where('partyId', employeePartyId,
                'attrName', 'JOB_TITLE').queryOne().attrValue == 'Software Engineer'

        GenericValue trainingTask = currentTask(caseId, 2)
        assert trainingTask
        Map trainingResult = submitTask(trainingTask, [
                trainingClassTypeId: 'BASIC_TRAINING',
                completionDate     : now,
                notes              : 'Initial training completed'
        ])
        assert trainingResult.formServiceName == 'onboardingCompleteTraining'
        assert from('PersonTraining').where('partyId', employeePartyId,
                'trainingClassTypeId', 'BASIC_TRAINING').queryOne()

        String assetName = "Onboarding Laptop ${stamp}".toString()
        String serialNumber = "SN-${stamp}".toString()
        GenericValue equipmentTask = currentTask(caseId, 3)
        assert equipmentTask
        Map equipmentResult = submitTask(equipmentTask, [
                fixedAssetTypeId: 'COMPUTER_HARDWARE',
                fixedAssetName  : assetName,
                serialNumber    : serialNumber,
                notes           : 'Laptop assigned'
        ])
        assert equipmentResult.formServiceName == 'onboardingAssignEquipment'
        GenericValue asset = from('FixedAsset').where('fixedAssetName', assetName).queryOne()
        assert asset
        assert asset.serialNumber == serialNumber
        assert from('PartyFixedAssetAssignment').where('partyId', employeePartyId,
                'fixedAssetId', asset.fixedAssetId).queryOne()

        String userLoginId = "onboard${stamp}".toString()
        GenericValue accountTask = currentTask(caseId, 4)
        assert accountTask
        Map accountResult = submitTask(accountTask, [
                userLoginId          : userLoginId,
                currentPassword      : 'Onboard1!',
                currentPasswordVerify: 'Onboard1!',
                requirePasswordChange: 'Y',
                notes                : 'Temporary account created'
        ])
        assert accountResult.formServiceName == 'onboardingCreateAccount'
        GenericValue userLogin = from('UserLogin').where('userLoginId', userLoginId).queryOne()
        assert userLogin
        assert userLogin.partyId == employeePartyId

        GenericValue completionTask = currentTask(caseId, 5)
        assert completionTask
        Map completionResult = submitTask(completionTask, [
                notes: 'Onboarding complete'
        ])
        assert completionResult.formServiceName == 'onboardingCompleteCase'

        GenericValue completedCase = from('Case').where('caseId', caseId).queryOne()
        assert completedCase.currentStageSeq == 0
        GenericValue workEffort = from('WorkEffort').where('workEffortId', caseId).queryOne()
        assert workEffort.currentStatusId == 'CASE_RESOLVED'
        assert from('PartyAttribute').where('partyId', employeePartyId,
                'attrName', 'ONBOARDING_STATUS').queryOne().attrValue == 'COMPLETED'
    }
}
