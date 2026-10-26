# casemgmt-humanres

`casemgmt` のケース管理カーネルを使った、人事オンボーディングの業務別プラグインです。

ケース管理カーネル側に人事固有の処理は置かず、このプラグインが `Person`、`PartyRole`、`Employment`、`PersonTraining`、`FixedAsset`、`UserLogin` とケースをつなぎます。

## オンボーディングフロー

```text
入社情報入力（開始フォーム）
  → 社員登録
  → 初回研修
  → 設備割当
  → システムアカウント発行
  → 完了確認
```

ケースタイプ ID は `ONBOARDING` です。

| ステージ | タスク | 主な処理 |
|---|---|---|
| Employee Registration | Register employee | 雇用関係、上長関係、職種を登録 |
| Training | Complete training | `PersonTraining` に研修完了を記録 |
| Equipment | Assign equipment | `FixedAsset` と `PartyFixedAssetAssignment` を作成 |
| Account | Create account | 標準 `createUserLogin` でアカウントを作成 |
| Completion | Confirm completion | 従業員の `PartyAttribute` に完了状態を記録 |

## 既存DBへの初回投入

`casemgmt-humanres` の seed データは、新規DB作成時には自動投入されます。既存DBへ追加した場合は、OFBiz を停止してから次のコマンドで投入してください。

```text
gradlew.bat "ofbiz --load-data readers=seed --load-data component=casemgmt-humanres"
```

投入後、OFBiz を再起動してください。データ投入前に必ずDBをバックアップしてください。

## デモデータ

`data/OnboardingDemoData.xml` に、オンボーディングをすぐ試せるデモ用マスタを用意しています。

- 部署: `DEMO_HR_DEPT`（`INTERNAL_ORGANIZATIO` ロール付き）
- 上長: `DEMO_HR_MANAGER`（`MANAGER` ロール付き）
- キュー担当: `ONBOARDING_QUEUE` に `admin`

ケースタイプとデモデータをまとめて投入する場合:

```text
gradlew.bat "ofbiz --load-data readers=seed,demo --load-data component=casemgmt-humanres"
```

デモデータだけを既存DBへ追加する場合:

```text
gradlew.bat "ofbiz --load-data readers=demo --load-data component=casemgmt-humanres"
```

デモデータは初回のみ投入してください。既に同じパーティ ID が存在する場合、重複キーになります。

## 開始方法

1. ケース管理の「ケース作成」から `ONBOARDING` を選択します。
2. `OnboardingStartForm` に従業員の氏名、個人メール、入社予定日を入力します。
3. 最初のタスクで所属部門のパーティ ID と上長のパーティ ID を入力します。
   - 部門には `INTERNAL_ORGANIZATIO` ロールが必要です。
   - 上長には `MANAGER` ロールが自動付与されます。
   - デモデータ投入済みなら、部門 `DEMO_HR_DEPT`、上長 `DEMO_HR_MANAGER` を指定できます。
4. 以降のタスクを順に完了します。

開始画面の URL 例:

```text
/casemgmt/control/StartCase?caseTypeId=ONBOARDING
```

## 設定・運用

- キュー: `ONBOARDING_QUEUE`
- 割当ポリシー: `ONBOARDING_POLICY`（キューから担当者が取得）
- SLA: `ONBOARDING_SLA`
- アクセスポリシー: `ONBOARDING_ACCESS`
- 業務権限: `HUMANRES_ONBOARDING` を `CASEMGMT_USER`、`FULLADMIN`、`SUPER` に付与
- 現在のサービス認可はケース管理の `CASEMGMT_CREATE` / `CASEMGMT_UPDATE` を使用

上長の入力には標準の `LookupPartyName`（パーティ検索）を使用します。検索画面を利用するには `PARTYMGR_VIEW` 権限が必要です。

既存DBへ権限だけを追加する場合は、OFBiz を停止してから次を実行し、再起動してください。

```text
gradlew.bat "ofbiz --load-data file=OnboardingPartyLookupPermissionSeedData.xml"
```

このコマンド用に、`OnboardingPartyLookupPermissionSeedData.xml` を一時的にプロジェクトルートへコピーして実行し、実行後に削除してください。新規DBでは `casemgmt-humanres` の seed として自動投入されます。

実際の運用では、`ONBOARDING_QUEUE` に人事担当者を `CaseQueueMember` として追加してください。アクセスポリシーにルールを追加する場合は、キューまたは従業員ロールに応じて設定します。

## データ連携

- 開始時に `Person` と個人メールの連絡先を作成
- 社員登録時に `PartyRole`、`Employment`、`PartyRelationship`、`PartyAttribute` を作成
- 研修完了時に `PersonTraining` を作成
- 設備割当時に `FixedAsset` と `PartyFixedAssetAssignment` を作成
- アカウント発行時に標準 `createUserLogin` を実行
- 完了時に `PartyAttribute.ONBOARDING_STATUS=COMPLETED` を設定

## フォーム

フォーム定義は `widget/OnboardingForms.xml` にあります。

- `OnboardingStartForm`
- `OnboardingEmployeeForm`
- `OnboardingTrainingForm`
- `OnboardingEquipmentForm`
- `OnboardingAccountForm`
- `OnboardingCompletionForm`

開始フォームと各タスクフォームは、ケース管理の `StartCaseSubmit` / `TaskFormSubmit` に接続されています。

## テスト

```text
gradlew.bat "ofbiz --test component=casemgmt-humanres"
```

テストでは次の流れを検証します。

- フォーム定義の target 検証
- 開始フォームからケースと従業員の作成
- 社員登録、研修、設備、アカウント、完了までの一連のタスク完了
- 標準エンティティへの登録内容

## 今後の拡張

- 部門・上長選択を標準パーティ検索に置き換える
- 入社通知、アカウント通知メール
- 休暇申請など他の人事ケースタイプ
- 入社後の probation 完了タスク
- 従業員番号や役職の正式な人事項目へのマッピング
