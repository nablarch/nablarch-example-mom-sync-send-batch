package com.nablarch.example;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import nablarch.core.date.SystemTimeUtil;
import nablarch.core.db.statement.ParameterizedSqlPStatement;
import nablarch.core.db.statement.SqlRow;
import nablarch.core.log.Logger;
import nablarch.core.log.LoggerManager;
import nablarch.core.text.FormatterUtil;
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
 *
 * @author Nabu Rakutaro
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
            inputData.put("PROJECT_START_DATE", FormatterUtil.format("dateTime", projectStartDate, DATE_FORMAT));
        }

        Date projectEndDate = inputData.getDate("PROJECT_END_DATE");
        if (projectEndDate != null) {
            inputData.put("PROJECT_END_DATE", FormatterUtil.format("dateTime", projectEndDate, DATE_FORMAT));
        }

        // 要求電文を送信する。
        // 送信時に、フレームワークが「要求電文のフォーマット定義」に従って、dataRecord変数の内容を電文に変換する。
        // SyncMessageオブジェクト生成時に指定している電文IDで、使用する「要求電文のフォーマット定義」ファイルが決定する。
        //
        // また、送信後は自動的に応答電文を待つ。
        // 応答電文の受信時には、「応答電文のフォーマット定義」に従って、フレームワークが電文の内容を取り出す。
        final SyncMessage responseMessage;
        try {
            responseMessage = MessageSender.sendSync(new SyncMessage("ProjectInsertMessage")
                    .addDataRecord(inputData));
            
        } catch (MessagingException e) {
            // 送信エラー
            throw new TransactionAbnormalEnd(100, e, "error.sendServer.fail");
        }

        handleResponse(new ResponseData(responseMessage.getDataRecord()));

        //処理成功(ここに到達した場合は、returnCodeが"success"である)
        return new Success();
    }

    /**
     * レスポンスに対する処理を行う。
     *
     * @param responseData レスポンスデータ
     */
    private static void handleResponse(final ResponseData responseData) {

        if (!responseData.hasReturnCode()) {
            // 通常この分岐に入ることはない
            // (到達した場合は、受信側でエラーハンドリングに失敗している)
            throw new TransactionAbnormalEnd(120, "error.receiveServer.fail");
        }
        
        if (responseData.isValidationError()) {
            throw new TransactionAbnormalEnd(110, "error.receiveServer.validation");
        } else if (responseData.isFatalError()) {
            throw new TransactionAbnormalEnd(110, "error.receiveServer.fail", responseData.getDetail());
        } else if (!responseData.isSuccess()) {
            throw new TransactionAbnormalEnd(120, "error.receiveServer.unknown",
                    responseData.getDetail(), responseData.getDetail());
        }
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

    /**
     * レスポンスデータ
     */
    private static final class ResponseData {

        /** レスポンスデータ */
        private final Map<String, Object> response;

        /** リターンコード */
        private final String returnCode;

        /**
         * レスポンスデータを生成する。
         * @param response レスポンス
         */
        private ResponseData(final Map<String, Object> response) {
            this.response = response;
            if (response != null) {
                returnCode = (String) response.get("returnCode");
            } else {
                returnCode = null;
            }
        }

        /**
         * 結果コードを持っているかどうか
         * @return 結果コードを持っている場合は{@code true}
         */
        boolean hasReturnCode() {
            return StringUtil.hasValue(returnCode);
        }

        /**
         * 処理が成功したかどうか
         *
         * @return 成功した場合は{@code true}
         */
        boolean isSuccess() {
            return Objects.equals(returnCode, "success");
        }

        /**
         * エラー内容がバリデーションエラーかどうか
         *
         * @return バリデーションエラーの場合{@code true}
         */
        boolean isValidationError() {
            return Objects.equals(returnCode, "error.validation");
        }

        /**
         * 予期せぬエラーが発生したかどうか
         * @return 予期せぬエラーが発生いた場合は{@code true}
         */
        boolean isFatalError() {
            return Objects.equals(returnCode, "fatal");
        }

        /**
         * 結果コードを取得する。
         * @return 結果コード
         */
        String getReturnCode() {
            return returnCode;
        }

        /**
         * 詳細情報を取得する。
         * @return 詳細情報
         */
        Object getDetail() {
            return response != null ? response.get("detail") : null;
        }
    }
}
