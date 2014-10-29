package com.hello.suripu.core.db;

import com.google.common.collect.ImmutableList;
import com.hello.suripu.core.db.mappers.AccountQuestionMapper;
import com.hello.suripu.core.db.mappers.QuestionMapper;
import com.hello.suripu.core.models.AccountQuestion;
import com.hello.suripu.core.models.Question;
import org.joda.time.DateTime;
import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.GetGeneratedKeys;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;
import org.skife.jdbi.v2.sqlobject.customizers.RegisterMapper;

/**
 * Created by kingshy on 10/24/14.
 */
public interface QuestionResponseDAO {

    @RegisterMapper(QuestionMapper.class)
    @SqlQuery("SELECT * FROM questions")
    ImmutableList<Question> getAllQuestions();

    @GetGeneratedKeys
    @SqlUpdate("INSERT INTO responses (account_id, question_id, account_question_id, response_id) VALUES " +
            "(:account_id, :question_id, :account_question_id, :response_id)")
    Long insertResponse(@Bind("account_id") long accountId,
                        @Bind("question_id") Integer questionId,
                        @Bind("account_question_id") Long accountQuestionId,
                        @Bind("response_id") Integer responseId);

    @GetGeneratedKeys
    @SqlUpdate("INSERT INTO responses (account_id, question_id, account_question_id, skip) VALUES " +
            "(:account_id, :question_id, :account_question_id, TRUE)")
    Long insertSkippedQuestion(@Bind("account_id") long accountId,
                               @Bind("question_id") Integer questionId,
                               @Bind("account_question_id") Long accountQuestionId);

    @GetGeneratedKeys
    @SqlUpdate("INSERT INTO account_questions " +
            "(account_id, question_id, created_local_utc_ts, expires_local_utc_ts) VALUES " +
            "(:account_id, :question_id, :created_local, :expires_local)")
    Long insertAccountQuestion(@Bind("account_id") long accountId,
                               @Bind("question_id") Integer questionId,
                               @Bind("created_local") DateTime createdLocal,
                               @Bind("expires_local") DateTime expiresLocal);

    @RegisterMapper(AccountQuestionMapper.class)
    @SqlQuery("SELECT * FROM account_questions WHERE " +
            "account_id = :account_id AND expires_local_utc_ts >= :expiration " +
            "ORDER BY id DESC")
    ImmutableList<AccountQuestion> getAccountQuestions(@Bind("account_id") long accountId,
                                                       @Bind("expiration") DateTime expiration);

}
