package ro.uvt.pokedex.core.model.tasks;

import lombok.Data;

@Data
public abstract class Task  {
    protected String initiator;
    protected String initiatedDate;
    protected String executionDate;
    protected Status status;
    protected String message;
    protected int attemptCount;
    protected int maxAttempts = 3;
    protected String nextAttemptAt;
    protected String lastErrorCode;
    protected String lastErrorMessage;
}
