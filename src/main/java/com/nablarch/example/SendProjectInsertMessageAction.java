package com.nablarch.example;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import nablarch.core.date.SystemTimeUtil;
import nablarch.core.db.statement.ParameterizedSqlPStatement;
import nablarch.core.db.statement.SqlRow;
import nablarch.core.log.Logger;
import nablarch.core.log.LoggerManager;
import nablarch.core.util.DateUtil;
import nablarch.core.util.StringUtil;
import nablarch.fw.DataReader;
import nablarch.fw.ExecutionContext;
import nablarch.fw.Result;
import nablarch.fw.Result.Success;
import nablarch.fw.action.BatchAction;
import nablarch.fw.messaging.MessageSender;
import nablarch.fw.messaging.MessagingException;
import nablarch.fw.messaging.SyncMessage;
import nablarch.fw.reader.DatabaseRecordReader;
import nablarch.fw.results.TransactionAbnormalEnd;

/**
 * プロジェクト登録メッセージ送信を行う業務アクションクラス。
 */
public class SendProjectInsertMessageAction extends BatchAction<SqlRow> {

    /** ロガー */
    private static final Logger LOGGER = LoggerManager.get(SendProjectInsertMessageAction.class);

    /**テーブルに格納されている日付を文字列型に変換する際に使用する書式。*/
    private static final String DATE_FORMAT = "yyyyMMdd";

    /**
     * {@inheritDoc}
     * <p/>
     * 同期応答メッセージ送信を行う。
     */
    @Override
    public Result handle(SqlRow inputData, ExecutionContext ctx) {
        LOGGER.logInfo("start");

        // 要求電文のデータレコードを用意するために、書式変換を行う。
        // (キーの大文字小文字変換はフレームワークが行うため実装不要
        Date projectStartDate = inputData.getDate("PROJECT_START_DATE");
        if (projectStartDate != null) {
            inputData.put("PROJECT_START_DATE", DateUtil.formatDate(projectStartDate, DATE_FORMAT));
        }

        Date projectEndDate = inputData.getDate("PROJECT_END_DATE");
        if (projectEndDate != null) {
            inputData.put("PROJECT_END_DATE", DateUtil.formatDate(projectEndDate, DATE_FORMAT));
        }

        // 要求電文を送信する。
        // 送信時に、フレームワークが「要求電文のフォーマット定義」に従って、dataRecord変数の内容を電文に変換する。
        // SyncMessageオブジェクト生成時に指定している電文IDで、使用する「要求電文のフォーマット定義」ファイルが決定する。
        //
        // また、送信後は自動的に応答電文を待つ。
        // 応答電文の受信時には、「応答電文のフォーマット定義」に従って、フレームワークが電文の内容を取り出す。
        SyncMessage responseMessage = null;
        try {
            responseMessage = MessageSender.sendSync(new SyncMessage("ProjectInsertMessage").addDataRecord(inputData));
        } catch (MessagingException e) {
            // 送信エラー
            throw new TransactionAbnormalEnd(100, e, "error.sendServer.fail");
        }

        Map<String, Object> responseDataRecord = responseMessage.getDataRecord();
        if (responseDataRecord != null && StringUtil.hasValue((String) responseDataRecord.get("returnCode"))) {
            String returnCode = (String) responseDataRecord.get("returnCode");
            String detail = (String) responseDataRecord.get("detail");

            if (returnCode.equals("error.validation")) {
                throw new TransactionAbnormalEnd(110, "error.receiveServer.validation");

            } else if (returnCode.equals("fatal")) {
                throw new TransactionAbnormalEnd(110, "error.receiveServer.fail", detail);

            } else if (!returnCode.equals("success")) {
                //通常到達しない。
                //(到達した場合は、受信側で未知のリターンコードが実装されている)
                throw new TransactionAbnormalEnd(120, "error.receiveServer.unknown", returnCode, detail);
            }
        } else {
            //通常到達しない。
            //(到達した場合は、受信側でエラーハンドリングに失敗している)
            throw new TransactionAbnormalEnd(120, "error.receiveServer.fail");
        }

        //処理成功(ここに到達した場合は、returnCodeが"success"である)
        return new Success();
    }

    /**
     * {@inheritDoc}
     * <p/>
     * 処理ステータスを正常終了に更新する。
     */
    @Override
    protected void transactionSuccess(SqlRow inputData, ExecutionContext context) {
        Map<String, Object> condition = new HashMap<>();
        condition.put("project_ins_req_id", inputData.getInteger("PROJECT_INS_REQ_ID"));
        condition.put("update_date", SystemTimeUtil.getTimestamp());
        ParameterizedSqlPStatement statement = getParameterizedSqlStatement("UPDATE_STATUS_NORMAL_END");
        statement.executeUpdateByMap(condition);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * 処理ステータスを異常終了に更新する。
     */
    @Override
    protected void transactionFailure(SqlRow inputData, ExecutionContext context) {
        Map<String, Object> condition = new HashMap<>();
        condition.put("project_ins_req_id", inputData.getInteger("PROJECT_INS_REQ_ID"));
        condition.put("update_date", SystemTimeUtil.getTimestamp());
        ParameterizedSqlPStatement statement = getParameterizedSqlStatement("UPDATE_STATUS_ABNORMAL_END");
        statement.executeUpdateByMap(condition);
    }


    /**
     * {@inheritDoc}
     * プロジェクト登録メッセージ送信要求一覧を読み込む{@link DataReader}を生成する。
     */
    @Override
    public DataReader<SqlRow> createReader(ExecutionContext ctx) {
        DatabaseRecordReader reader = new DatabaseRecordReader();
        reader.setStatement(getSqlPStatement("GET_PROJECT_INS_REQ_LIST"));
        return reader;
    }
}
