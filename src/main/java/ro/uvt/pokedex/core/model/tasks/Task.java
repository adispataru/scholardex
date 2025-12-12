package ro.uvt.pokedex.core.model.tasks;

import lombok.Data;

@Data
public abstract class Task  {
    protected String initiator;
    protected String initiatedDate;
    protected String executionDate;
    protected Status status;
    protected String message;
}
