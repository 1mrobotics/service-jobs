package com.epam.reportportal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DeleteResponse {
	@JsonProperty("took")
	private int took;

	@JsonProperty("timed_out")
	private boolean timedOut;

	@JsonProperty("total")
	private int total;

	@JsonProperty("deleted")
	private int deleted;

	public DeleteResponse() {
	}

	public DeleteResponse(int took, boolean timedOut, int total, int deleted) {
		this.took = took;
		this.timedOut = timedOut;
		this.total = total;
		this.deleted = deleted;
	}

	public int getTook() {
		return took;
	}

	public void setTook(int took) {
		this.took = took;
	}

	public boolean isTimedOut() {
		return timedOut;
	}

	public void setTimedOut(boolean timedOut) {
		this.timedOut = timedOut;
	}

	public int getTotal() {
		return total;
	}

	public void setTotal(int total) {
		this.total = total;
	}

	public int getDeleted() {
		return deleted;
	}

	public void setDeleted(int deleted) {
		this.deleted = deleted;
	}
}
