--------------------------------------------------------------------------------
-- プロジェクト登録要求一覧を取得する。
--------------------------------------------------------------------------------
GET_PROJECT_INS_REQ_LIST =
SELECT
    PROJECT_INS_REQ_ID,
    PROJECT_NAME,
    PROJECT_TYPE,
    PROJECT_CLASS,
    PROJECT_START_DATE,
    PROJECT_END_DATE,
    CLIENT_ID,
    PROJECT_MANAGER,
    PROJECT_LEADER,
    USER_ID,
    NOTE,
    SALES,
    COST_OF_GOODS_SOLD ,
    SGA,
    ALLOCATION_OF_CORP_EXPENSES
FROM
    PROJECT_INS_REQ
WHERE
    STATUS = '0'
ORDER BY
    PROJECT_INS_REQ_ID


--------------------------------------------------------------------------------
-- 処理ステータスを'1'(処理済み)に更新するSQL
--------------------------------------------------------------------------------
UPDATE_STATUS_NORMAL_END =
UPDATE
    PROJECT_INS_REQ
SET
    STATUS = '1',
    UPDATE_DATE = :update_date
WHERE
    PROJECT_INS_REQ_ID = :project_ins_req_id

--------------------------------------------------------------------------------
-- 処理ステータスを'2'(エラー)に更新するSQL
--------------------------------------------------------------------------------
UPDATE_STATUS_ABNORMAL_END =
UPDATE
    PROJECT_INS_REQ
SET
    STATUS = '2',
    UPDATE_DATE = :update_date
WHERE
    PROJECT_INS_REQ_ID = :project_ins_req_id