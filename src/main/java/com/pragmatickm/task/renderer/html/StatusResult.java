/*
 * pragmatickm-task-renderer-html - Tasks rendered as HTML in a Servlet environment.
 * Copyright (C) 2013, 2014, 2015, 2016, 2017  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of pragmatickm-task-renderer-html.
 *
 * pragmatickm-task-renderer-html is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * pragmatickm-task-renderer-html is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with pragmatickm-task-renderer-html.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.pragmatickm.task.renderer.html;

import com.aoindustries.util.UnmodifiableCalendar;
import com.pragmatickm.task.model.TaskLog;
import java.util.Calendar;

public class StatusResult {

	/**
	 * The CSS classes for different overall statuses.
	 */
	public enum StatusCssClass {
		task_status_new,
		task_status_new_waiting_do_after,
		task_status_in_future,
		task_status_due_today,
		task_status_due_today_waiting_do_after,
		task_status_late,
		task_status_late_waiting_do_after,
		task_status_progress,
		task_status_progress_waiting_do_after,
		task_status_completed,
		task_status_missed;

		/**
		 * Gets the CSS class for a given TaskLog status.
		 */
		public static StatusCssClass getStatusCssClass(TaskLog.Status taskLogStatus) {
			switch(taskLogStatus) {
				case PROGRESS : return task_status_progress;
				case COMPLETED : return task_status_completed;
				case NOTHING_TO_DO : return task_status_completed;
				case MISSED : return task_status_missed;
				default : throw new AssertionError("Unexpected value for taskLogStatus: " + taskLogStatus);
			}
		}

		public static StatusCssClass getStatusDoBeforeCssClass(TaskLog.Status taskLogStatus) {
			switch(taskLogStatus) {
				case PROGRESS : return task_status_progress_waiting_do_after;
				case COMPLETED : return task_status_completed;
				case NOTHING_TO_DO : return task_status_completed;
				case MISSED : return task_status_missed;
				default : throw new AssertionError("Unexpected value for taskLogStatus: " + taskLogStatus);
			}
		}
	}

	private final StatusCssClass cssClass;
	private final String description;
	private final String comments;
	private final boolean completedSchedule;
	private final boolean readySchedule;
	private final boolean futureSchedule;
	private final UnmodifiableCalendar date;

	StatusResult(
		StatusCssClass cssClass,
		String description,
		String comments,
		boolean completedSchedule,
		boolean readySchedule,
		boolean futureSchedule,
		Calendar date
	) {
		if(completedSchedule && readySchedule) throw new AssertionError("A task may not be both completed and ready");
		if(readySchedule && futureSchedule) throw new AssertionError("A task may not be both ready and future");
		this.cssClass = cssClass;
		this.description = description;
		this.comments = comments;
		this.completedSchedule = completedSchedule;
		this.readySchedule = readySchedule;
		this.futureSchedule = futureSchedule;
		this.date = UnmodifiableCalendar.wrap(date);
	}
	StatusResult(
		TaskLog.Status taskStatus,
		String comments,
		boolean allDoBeforesCompleted,
		boolean futureSchedule,
		Calendar date
	) {
		if(allDoBeforesCompleted) {
			this.cssClass = StatusCssClass.getStatusCssClass(taskStatus);
			this.description = taskStatus.getLabel();
		} else {
			this.cssClass = StatusCssClass.getStatusDoBeforeCssClass(taskStatus);
			this.description = taskStatus.getLabelDoBefore();
		}
		this.comments = comments;
		this.completedSchedule = taskStatus.isCompletedSchedule();
		this.readySchedule = allDoBeforesCompleted && !taskStatus.isCompletedSchedule();
		this.futureSchedule = futureSchedule;
		this.date = UnmodifiableCalendar.wrap(date);
	}

	public StatusCssClass getCssClass() {
		return cssClass;
	}

	public String getDescription() {
		return description;
	}

	public String getComments() {
		return comments;
	}

	public boolean isCompletedSchedule() {
		return completedSchedule;
	}

	public boolean isReadySchedule() {
		return readySchedule;
	}

	public boolean isFutureSchedule() {
		return futureSchedule;
	}

	public UnmodifiableCalendar getDate() {
		return date;
	}
}
