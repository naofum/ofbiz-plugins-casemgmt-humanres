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
package org.apache.ofbiz.casemgmt.humanres

import java.sql.Timestamp

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.casemgmt.cases.CaseContextHelper
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * Employee onboarding business services.
 *
 * <p>The case-management kernel remains HR-agnostic. This plugin owns the
 * Person, PartyRole, Employment, PersonTraining, FixedAsset and UserLogin
 * integration used by the onboarding case type.</p>
 */
Map onboardingStartCase() {
    String firstName = text(parameters.firstName)
    String middleName = text(parameters.middleName)
    String lastName = text(parameters.lastName)
    if (!firstName || !lastName) {
        return errorMap('First name and last name are required')
    }
    Map personResult = dispatcher.runSync('createPerson', [
            userLogin : userLogin,
            firstName : firstName,
            middleName: middleName,
            lastName  : lastName
    ])
    if (ServiceUtil.isError(personResult)) {
        return personResult
    }
    String partyId = personResult.partyId?.toString()
    if (!partyId) {
        return errorMap('Employee party was not created')
    }

    Timestamp now = UtilDateTime.nowTimestamp()
    String email = text(parameters.personalEmail)
    if (email) {
        String contactMechId = delegator.getNextSeqId('ContactMech')
        delegator.create('ContactMech', [
                contactMechId    : contactMechId,
                contactMechTypeId: 'EMAIL_ADDRESS',
                infoString       : email
        ])
        delegator.create('PartyContactMech', [
                partyId    : partyId,
                contactMechId: contactMechId,
                fromDate   : now,
                allowSolicitation: 'N'
        ])
        delegator.create('PartyContactMechPurpose', [
                partyId              : partyId,
                contactMechId        : contactMechId,
                contactMechPurposeTypeId: 'PRIMARY_EMAIL',
                fromDate             : now
        ])
    }
    String targetStartDate = text(parameters.targetStartDate)
    if (targetStartDate) {
        setPartyAttribute(partyId, 'TARGET_START_DATE', targetStartDate)
    }
    String description = text(parameters.description)
    if (!description) {
        description = "Employee onboarding: ${firstName} ${lastName}".toString()
    }
    return successMap([
            partyId          : partyId,
            customerPartyId  : partyId,
            businessEntityName: 'Party',
            businessKeys     : [partyId: partyId],
            description      : description
    ])
}

Map onboardingRegisterEmployee() {
    String caseId = text(parameters.caseId)
    String employeePartyId = findEmployeePartyId(caseId, parameters.employeePartyId)
    if (!employeePartyId) {
        return errorMap('Onboarding employee is not linked to the case')
    }
    String departmentPartyId = text(parameters.departmentPartyId)
    if (!departmentPartyId) {
        return errorMap('Department party ID is required')
    }
    if (!partyExists(departmentPartyId)) {
        return errorMap("Department not found: $departmentPartyId")
    }
    if (!hasPartyRole(departmentPartyId, 'INTERNAL_ORGANIZATIO')) {
        return errorMap("Department is not an internal organization: $departmentPartyId")
    }
    ensurePartyRole(employeePartyId, 'EMPLOYEE')

    Timestamp now = UtilDateTime.nowTimestamp()
    Timestamp fromDate = toTimestamp(parameters.employmentFromDate, now)
    boolean hasOpenEmployment = EntityQuery.use(delegator).from('Employment')
            .where(EntityCondition.makeCondition([
                    partyIdTo   : employeePartyId,
                    roleTypeIdTo: 'EMPLOYEE'
            ])).queryList().any { GenericValue employment -> !employment.thruDate }
    if (!hasOpenEmployment) {
        delegator.create('Employment', [
                roleTypeIdFrom: 'INTERNAL_ORGANIZATIO',
                roleTypeIdTo  : 'EMPLOYEE',
                partyIdFrom   : departmentPartyId,
                partyIdTo     : employeePartyId,
                fromDate      : fromDate
        ])
    }

    String managerPartyId = text(parameters.managerPartyId)
    if (managerPartyId) {
        if (!partyExists(managerPartyId)) {
            return errorMap("Manager not found: $managerPartyId")
        }
        ensurePartyRole(managerPartyId, 'MANAGER')
        boolean hasManager = EntityQuery.use(delegator).from('PartyRelationship')
                .where(EntityCondition.makeCondition([
                        partyIdFrom         : managerPartyId,
                        roleTypeIdFrom      : 'MANAGER',
                        partyIdTo           : employeePartyId,
                        roleTypeIdTo        : 'EMPLOYEE',
                        partyRelationshipTypeId: 'MANAGER'
                ])).queryList().any { GenericValue relationship -> !relationship.thruDate }
        if (!hasManager) {
            delegator.create('PartyRelationship', [
                    partyIdFrom            : managerPartyId,
                    roleTypeIdFrom         : 'MANAGER',
                    partyIdTo              : employeePartyId,
                    roleTypeIdTo           : 'EMPLOYEE',
                    partyRelationshipTypeId: 'MANAGER',
                    fromDate               : fromDate,
                    relationshipName       : 'Manager',
                    comments               : text(parameters.notes)
            ])
        }
    }

    String jobTitle = text(parameters.jobTitle)
    if (jobTitle) {
        setPartyAttribute(employeePartyId, 'JOB_TITLE', jobTitle)
    }
    setPartyAttribute(employeePartyId, 'DEPARTMENT_PARTY_ID', departmentPartyId)
    return successMap([employeePartyId: employeePartyId])
}

Map onboardingCompleteTraining() {
    String caseId = text(parameters.caseId)
    String employeePartyId = findEmployeePartyId(caseId, parameters.employeePartyId)
    if (!employeePartyId) {
        return errorMap('Onboarding employee is not linked to the case')
    }
    String trainingClassTypeId = text(parameters.trainingClassTypeId)
    if (!trainingClassTypeId) {
        return errorMap('Training type is required')
    }
    if (EntityQuery.use(delegator).from('TrainingClassType')
            .where('trainingClassTypeId', trainingClassTypeId).queryOne() == null) {
        return errorMap("Training type not found: $trainingClassTypeId")
    }
    Timestamp now = UtilDateTime.nowTimestamp()
    Timestamp completionDate = toTimestamp(parameters.completionDate, now)
    Map<String, Object> fields = [
            thruDate      : completionDate,
            approvalStatus: 'TRAINING_APPROVED',
            reason        : text(parameters.notes)
    ]
    GenericValue training = EntityQuery.use(delegator).from('PersonTraining')
            .where(EntityCondition.makeCondition([
                    partyId           : employeePartyId,
                    trainingClassTypeId: trainingClassTypeId,
                    fromDate          : completionDate
            ])).queryOne()
    if (training == null) {
        fields.partyId = employeePartyId
        fields.trainingClassTypeId = trainingClassTypeId
        fields.fromDate = completionDate
        delegator.create('PersonTraining', fields)
    } else {
        training.setFields(fields)
        training.store()
    }
    return successMap([employeePartyId: employeePartyId])
}

Map onboardingAssignEquipment() {
    String caseId = text(parameters.caseId)
    String employeePartyId = findEmployeePartyId(caseId, parameters.employeePartyId)
    if (!employeePartyId) {
        return errorMap('Onboarding employee is not linked to the case')
    }
    String fixedAssetTypeId = text(parameters.fixedAssetTypeId)
    if (!fixedAssetTypeId) {
        return errorMap('Asset type is required')
    }
    if (EntityQuery.use(delegator).from('FixedAssetType')
            .where('fixedAssetTypeId', fixedAssetTypeId).queryOne() == null) {
        return errorMap("Asset type not found: $fixedAssetTypeId")
    }
    ensurePartyRole(employeePartyId, 'EMPLOYEE')
    String fixedAssetName = text(parameters.fixedAssetName)
    if (!fixedAssetName) {
        return errorMap('Asset name is required')
    }
    Timestamp now = UtilDateTime.nowTimestamp()
    String fixedAssetId = delegator.getNextSeqId('FixedAsset')
    delegator.create('FixedAsset', [
            fixedAssetId    : fixedAssetId,
            fixedAssetTypeId: fixedAssetTypeId,
            fixedAssetName  : fixedAssetName,
            serialNumber    : text(parameters.serialNumber),
            dateAcquired    : now,
            partyId         : employeePartyId,
            roleTypeId      : 'EMPLOYEE'
    ])
    delegator.create('PartyFixedAssetAssignment', [
            partyId     : employeePartyId,
            roleTypeId  : 'EMPLOYEE',
            fixedAssetId: fixedAssetId,
            fromDate    : now,
            allocatedDate: now,
            statusId    : 'PRTYASGN_ASSIGNED',
            comments    : text(parameters.notes)
    ])
    return successMap([employeePartyId: employeePartyId, fixedAssetId: fixedAssetId])
}

Map onboardingCreateAccount() {
    String caseId = text(parameters.caseId)
    String employeePartyId = findEmployeePartyId(caseId, parameters.employeePartyId)
    if (!employeePartyId) {
        return errorMap('Onboarding employee is not linked to the case')
    }
    String userLoginId = text(parameters.userLoginId)
    String currentPassword = parameters.currentPassword?.toString() ?: ''
    String currentPasswordVerify = parameters.currentPasswordVerify?.toString() ?: ''
    if (!userLoginId || !currentPassword) {
        return errorMap('User login ID and temporary password are required')
    }
    if (currentPassword != currentPasswordVerify) {
        return errorMap('Temporary password and verification do not match')
    }
    if (EntityQuery.use(delegator).from('UserLogin')
            .where('userLoginId', userLoginId).queryOne() != null) {
        return errorMap("User login already exists: $userLoginId")
    }
    Map result = dispatcher.runSync('createUserLogin', [
            userLogin            : userLogin,
            userLoginId          : userLoginId,
            currentPassword      : currentPassword,
            currentPasswordVerify: currentPasswordVerify,
            enabled              : 'Y',
            requirePasswordChange: text(parameters.requirePasswordChange) ?: 'Y',
            partyId              : employeePartyId
    ])
    if (ServiceUtil.isError(result)) {
        return result
    }
    return successMap([
            employeePartyId  : employeePartyId,
            createdUserLoginId: userLoginId
    ])
}

Map onboardingCompleteCase() {
    String caseId = text(parameters.caseId)
    String employeePartyId = findEmployeePartyId(caseId, parameters.employeePartyId)
    if (!employeePartyId) {
        return errorMap('Onboarding employee is not linked to the case')
    }
    if (EntityQuery.use(delegator).from('Employment')
            .where('partyIdTo', employeePartyId).queryOne() == null) {
        return errorMap('Employment has not been registered')
    }
    if (EntityQuery.use(delegator).from('PersonTraining')
            .where('partyId', employeePartyId).queryOne() == null) {
        return errorMap('Initial training has not been completed')
    }
    if (EntityQuery.use(delegator).from('PartyFixedAssetAssignment')
            .where('partyId', employeePartyId).queryOne() == null) {
        return errorMap('Equipment has not been assigned')
    }
    if (EntityQuery.use(delegator).from('UserLogin')
            .where('partyId', employeePartyId).queryOne() == null) {
        return errorMap('System account has not been created')
    }
    String now = UtilDateTime.nowTimestamp().toString()
    setPartyAttribute(employeePartyId, 'ONBOARDING_STATUS', 'COMPLETED')
    setPartyAttribute(employeePartyId, 'ONBOARDING_COMPLETED_DATE', now)
    return successMap([employeePartyId: employeePartyId])
}

private String findEmployeePartyId(String caseId, Object directPartyId) {
    String direct = text(directPartyId)
    if (direct) {
        return direct
    }
    if (!caseId) {
        return null
    }
    GenericValue context = EntityQuery.use(delegator).from('CaseContext')
            .where(EntityCondition.makeCondition([
                    caseId    : caseId,
                    entityName: 'Party'
            ])).orderBy('-contextSeq').queryFirst()
    if (context == null) {
        return null
    }
    Map<String, String> keys = CaseContextHelper.loadKeys(delegator, context)
    return keys.partyId ?: context.pkValue
}

private boolean partyExists(String partyId) {
    return EntityQuery.use(delegator).from('Party')
            .where('partyId', partyId).queryOne() != null
}

private boolean hasPartyRole(String partyId, String roleTypeId) {
    return EntityQuery.use(delegator).from('PartyRole')
            .where(EntityCondition.makeCondition([
                    partyId   : partyId,
                    roleTypeId: roleTypeId
            ])).queryOne() != null
}

private void ensurePartyRole(String partyId, String roleTypeId) {
    if (!hasPartyRole(partyId, roleTypeId)) {
        delegator.create('PartyRole', [partyId: partyId, roleTypeId: roleTypeId])
    }
}

private void setPartyAttribute(String partyId, String attrName, String attrValue) {
    if (!partyId || !attrName || attrValue == null) {
        return
    }
    GenericValue attribute = EntityQuery.use(delegator).from('PartyAttribute')
            .where(EntityCondition.makeCondition([
                    partyId : partyId,
                    attrName: attrName
            ])).queryOne()
    if (attribute == null) {
        delegator.create('PartyAttribute', [
                partyId : partyId,
                attrName: attrName,
                attrValue: attrValue
        ])
    } else {
        attribute.attrValue = attrValue
        attribute.store()
    }
}

private Timestamp toTimestamp(Object value, Timestamp fallback) {
    if (value instanceof Timestamp) {
        return value
    }
    if (value instanceof Date) {
        return new Timestamp((value as Date).time)
    }
    String text = value?.toString()?.trim()
    if (text) {
        try {
            return Timestamp.valueOf(text.length() == 10 ? text + ' 00:00:00' : text)
        } catch (Exception ignored) {
            return fallback
        }
    }
    return fallback
}

private String text(Object value) {
    String text = value?.toString()?.trim()
    return text ? text : null
}

private Map successMap(Map values) {
    Map result = ServiceUtil.returnSuccess()
    result.putAll(values)
    return result
}

private Map errorMap(String message) {
    return ServiceUtil.returnError(message)
}
