/*
 * pragmatickm-task-renderer-html - Tasks rendered as HTML in a Servlet environment.
 * Copyright (C) 2013, 2014, 2015, 2016, 2017, 2020  AO Industries, Inc.
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
	public enum Style {
		NEW                       ("pragmatickm-task-status-new"),
		NEW_WAITING_DO_AFTER      ("pragmatickm-task-status-new-waiting-do-after"),
		IN_FUTURE                 ("pragmatickm-task-status-in-future"),
		DUE_TODAY                 ("pragmatickm-task-status-due-today"),
		DUE_TODAY_WAITING_DO_AFTER("pragmatickm-task-status-due-today-waiting-do-after"),
		LATE                      ("pragmatickm-task-status-late"),
		LATE_WAITING_DO_AFTER     ("pragmatickm-task-status-late-waiting-do-after"),
		PROGRESS                  ("pragmatickm-task-status-progress"),
		PROGRESS_WAITING_DO_AFTER ("pragmatickm-task-status-progress-waiting-do-after"),
		COMPLETED                 ("pragmatickm-task-status-completed"),
		MISSED                    ("pragmatickm-task-status-missed");

		private final String cssClass;

		private Style(String cssClass) {
			this.cssClass = cssClass;
		}

		public String getCssClass() {
			return cssClass;
		}

		/**
		 * Gets the CSS class for a given TaskLog status.
		 */
		public static Style getStyle(TaskLog.Status taskLogStatus) {
			switch(taskLogStatus) {
				case PROGRESS      : return PROGRESS;
				case COMPLETED     : return COMPLETED;
				case NOTHING_TO_DO : return COMPLETED;
				case MISSED        : return MISSED;
				default            : throw new AssertionError("Unexpected value for taskLogStatus: " + taskLogStatus);
			}
		}

		public static Style getStyleDoBefore(TaskLog.Status taskLogStatus) {
			switch(taskLogStatus) {
				case PROGRESS      : return PROGRESS_WAITING_DO_AFTER;
				case COMPLETED     : return COMPLETED;
				case NOTHING_TO_DO : return COMPLETED;
				case MISSED        : return MISSED;
				default            : throw new AssertionError("Unexpected value for taskLogStatus: " + taskLogStatus);
			}
		}
	}

	private final Style style;
	private final String description;
	private final String comments;
	private final boolean completedSchedule;
	private final boolean readySchedule;
	private final boolean futureSchedule;
	private final UnmodifiableCalendar date;

	StatusResult(
		Style style,
		String description,
		String comments,
		boolean completedSchedule,
		boolean readySchedule,
		boolean futureSchedule,
		Calendar date
	) {
		if(completedSchedule && readySchedule) throw new AssertionError("A task may not be both completed and ready");
		if(readySchedule && futureSchedule) throw new AssertionError("A task may not be both ready and future");
		this.style = style;
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
			this.style = Style.getStyle(taskStatus);
			this.description = taskStatus.getLabel();
		} else {
			this.style = Style.getStyleDoBefore(taskStatus);
			this.description = taskStatus.getLabelDoBefore();
		}
		this.comments = comments;
		this.completedSchedule = taskStatus.isCompletedSchedule();
		this.readySchedule = allDoBeforesCompleted && !taskStatus.isCompletedSchedule();
		this.futureSchedule = futureSchedule;
		this.date = UnmodifiableCalendar.wrap(date);
	}

	public Style getStyle() {
		return style;
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
