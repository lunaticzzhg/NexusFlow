ALTER TABLE response_run_results
    DROP CONSTRAINT response_run_results_type_check;

ALTER TABLE response_run_results
    ADD CONSTRAINT response_run_results_type_check CHECK (
        result_type IN ('CONVERSATION_ANSWER', 'PLANNING_UNDERSTANDING', 'PLANNING_RESULT')
    );
