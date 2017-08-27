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

import com.aoindustries.encoding.Coercion;
import com.aoindustries.encoding.MediaWriter;
import com.aoindustries.encoding.TextInXhtmlAttributeEncoder;
import static com.aoindustries.encoding.TextInXhtmlAttributeEncoder.encodeTextInXhtmlAttribute;
import static com.aoindustries.encoding.TextInXhtmlAttributeEncoder.textInXhtmlAttributeEncoder;
import static com.aoindustries.encoding.TextInXhtmlEncoder.encodeTextInXhtml;
import com.aoindustries.io.buffer.BufferResult;
import com.aoindustries.net.Path;
import static com.aoindustries.taglib.AttributeUtils.resolveValue;
import com.aoindustries.util.CalendarUtils;
import com.aoindustries.util.WrappedException;
import com.aoindustries.util.schedule.Recurring;
import com.aoindustries.validation.ValidationException;
import com.pragmatickm.task.model.Priority;
import com.pragmatickm.task.model.Task;
import com.pragmatickm.task.model.TaskException;
import com.pragmatickm.task.model.TaskPriority;
import com.semanticcms.core.controller.Cache;
import com.semanticcms.core.controller.CacheFilter;
import com.semanticcms.core.controller.CapturePage;
import com.semanticcms.core.model.BookRef;
import com.semanticcms.core.model.Element;
import com.semanticcms.core.model.ElementContext;
import com.semanticcms.core.model.ElementRef;
import com.semanticcms.core.model.NodeBodyWriter;
import com.semanticcms.core.model.Page;
import com.semanticcms.core.model.PageRef;
import com.semanticcms.core.model.ResourceRef;
import com.semanticcms.core.pages.CaptureLevel;
import com.semanticcms.core.pages.local.CurrentPage;
import com.semanticcms.core.renderer.html.HtmlRenderer;
import com.semanticcms.core.renderer.html.PageIndex;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.el.ELContext;
import javax.el.ValueExpression;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

final public class TaskHtmlRenderer {

	private static final String REMOVE_JSP_EXTENSION = ".jsp";
	private static final String REMOVE_JSPX_EXTENSION = ".jspx";
	private static final String TASKLOG_MID = "-tasklog-";
	private static final String TASKLOG_EXTENSION = ".xml";

	private static void writeRow(String header, String value, Writer out) throws IOException {
		if(value != null) {
			out.write("<tr><th>");
			encodeTextInXhtml(header, out);
			out.write("</th><td colspan=\"3\">");
			encodeTextInXhtml(value, out);
			out.write("</td></tr>\n");
		}
	}

	private static void writeRow(String header, List<?> values, Writer out) throws IOException {
		if(values != null) {
			int size = values.size();
			if(size > 0) {
				out.write("<tr><th>");
				encodeTextInXhtml(header, out);
				out.write("</th><td colspan=\"3\">");
				for(int i=0; i<size; i++) {
					encodeTextInXhtml(values.get(i).toString(), out);
					if(i != (size - 1)) out.write("<br />");
				}
				out.write("</td></tr>\n");
			}
		}
	}

	private static void writeRow(String header, Calendar date, Writer out) throws IOException {
		if(date != null) writeRow(header, CalendarUtils.formatDate(date), out);
	}

	private static void writeRow(String header, Recurring recurring, boolean relative, Writer out) throws IOException {
		if(recurring != null) {
			writeRow(
				header,
				relative
					? (recurring.getRecurringDisplay() + " (Relative)")
					: recurring.getRecurringDisplay(),
				out
			);
		}
	}

	public static void writeBeforeBody(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		CaptureLevel captureLevel,
		Writer out,
		Task task,
		Object style
	) throws TaskException, IOException, ServletException {
		final Page currentPage = CurrentPage.getCurrentPage(request);

		// Verify consistency between attributes
		Recurring recurring = task.getRecurring();
		boolean relative = task.getRelative();
		if(recurring!=null) {
			Calendar on = task.getOn();
			if(on == null) {
				if(!relative) throw new ServletException("Task \"on\" attribute required for non-relative recurring tasks.");
			} else {
				String checkResult = recurring.checkScheduleFrom(on, "on");
				if(checkResult != null) throw new ServletException("Task: " + checkResult);
			}
		} else {
			if(relative) {
				throw new ServletException("Task \"relative\" attribute only allowed for recurring tasks.");
			}
		}

		if(captureLevel == CaptureLevel.BODY) {
			Cache cache = CacheFilter.getCache(request);
			// Capture the doBefores
			List<Task> doBefores;
			{
				// TODO: Concurrent getDoBefores?
				Set<ElementRef> doBeforeRefs = task.getDoBefores();
				int size = doBeforeRefs.size();
				doBefores = new ArrayList<Task>(size);
				// TODO: Concurrent capture here?
				for(ElementRef doBefore : doBeforeRefs) {
					Element elem = CapturePage.capturePage(
						servletContext,
						request,
						response,
						doBefore.getPageRef(),
						CaptureLevel.META
					).getElementsById().get(doBefore.getId());
					if(elem == null) throw new TaskException("Element not found: " + doBefore);
					if(!(elem instanceof Task)) throw new TaskException("Element is not a Task: " + elem.getClass().getName());
					if(elem.getPage().getGeneratedIds().contains(elem.getId())) throw new TaskException("Not allowed to reference task by generated id, set an explicit id on the task: " + elem);
					doBefores.add((Task)elem);
				}
			}
			// Find the doAfters
			List<Task> doAfters = TaskUtil.getDoAfters(servletContext, request, response, task);
			// Lookup all the statuses at once
			Map<Task,StatusResult> statuses;
			{
				Set<Task> allTasks = new HashSet<Task>(
					(
						doBefores.size()
						+ 1 // this task
						+ doAfters.size()
					) *4/3+1
				);
				allTasks.addAll(doBefores);
				allTasks.add(task);
				allTasks.addAll(doAfters);
				statuses = TaskUtil.getMultipleStatuses(servletContext, request, response, allTasks, cache);
			}
			// Write the task itself to this page
			final PageIndex pageIndex = PageIndex.getCurrentPageIndex(request);
			out.write("<table id=\"");
			PageIndex.appendIdInPage(
				pageIndex,
				currentPage,
				task.getId(),
				new MediaWriter(TextInXhtmlAttributeEncoder.textInXhtmlAttributeEncoder, out)
			);
			out.write("\" class=\"thinTable taskTable\"");
			style = Coercion.nullIfEmpty(style);
			if(style != null) {
				out.write(" style=\"");
				Coercion.write(style, textInXhtmlAttributeEncoder, out);
				out.write('"');
			}
			out.write(">\n"
					+ "<thead><tr><th class=\"taskTableHeader\" colspan=\"4\"><div>");
			encodeTextInXhtml(task.getLabel(), out);
			out.write("</div></th></tr></thead>\n"
					+ "<tbody>\n");
			final long now = System.currentTimeMillis();
			writeTasks(servletContext, request, response, out, cache, currentPage, now, doBefores, statuses, "Do Before:");
			out.write("<tr><th>Status:</th><td class=\"");
			StatusResult status = statuses.get(task);
			encodeTextInXhtmlAttribute(status.getCssClass().name(), out);
			out.write("\" colspan=\"3\">");
			encodeTextInXhtml(status.getDescription(), out);
			out.write("</td></tr>\n");
			String comments = status.getComments();
			if(comments != null && !comments.isEmpty()) {
				out.write("<tr><th>Status Comment:</th><td colspan=\"3\">");
				encodeTextInXhtml(comments, out);
				out.write("</td></tr>\n");
			}
			List<TaskPriority> taskPriorities = task.getPriorities();
			for(int i=0, size=taskPriorities.size(); i<size; i++) {
				TaskPriority taskPriority = taskPriorities.get(i);
				out.write("<tr>");
				if(i==0) {
					out.write("<th");
					if(size != 1) {
						out.write(" rowspan=\"");
						out.write(Integer.toString(size));
						out.write('"');
					}
					out.write(">Priority:</th>");
				}
				out.write("<td class=\"");
				Priority priority = taskPriority.getPriority();
				encodeTextInXhtmlAttribute(priority.getCssClass(), out);
				out.write("\" colspan=\"3\">");
				encodeTextInXhtml(taskPriority.toString(), out);
				out.write("</td></tr>\n");
			}
			writeRow(recurring==null ? "On:" : "Starting:", task.getOn(), out);
			writeRow("Recurring:", recurring, relative, out);
			writeRow("Assigned To:", task.getAssignedTo(), out);
			writeRow("Pay:", task.getPay(), out);
			writeRow("Cost:", task.getCost(), out);
			writeTasks(servletContext, request, response, out, cache, currentPage, now, doAfters, statuses, "Do After:");
		}
	}

	/**
	 * @param style  ValueExpression that returns Object, only evaluated for BODY capture level
	 */
	public static void writeBeforeBody(
		ServletContext servletContext,
		ELContext elContext,
		HttpServletRequest request,
		HttpServletResponse response,
		CaptureLevel captureLevel,
		Writer out,
		Task task,
		ValueExpression style
	) throws TaskException, IOException, ServletException {
		writeBeforeBody(
			servletContext,
			request,
			response,
			captureLevel,
			out,
			task,
			captureLevel == CaptureLevel.BODY ? resolveValue(style, Object.class, elContext) : null
		);
	}

	public static void writeAfterBody(Task task, Writer out, ElementContext context) throws IOException {
		BufferResult body = task.getBody();
		if(body.getLength() > 0) {
			out.write("<tr><td colspan=\"4\">\n");
			body.writeTo(new NodeBodyWriter(task, out, context));
			out.write("\n</td></tr>\n");
		}
		out.write("</tbody>\n"
				+ "</table>");
	}

	/**
	 * Gets the file that stores the XML data for a task log.
	 */
	public static ResourceRef getTaskLogXmlFile(PageRef pageRef, String taskId) {
		String xmlFilePath = pageRef.getPath().toString();
		if(xmlFilePath.endsWith(Path.SEPARATOR_STRING)) {
			xmlFilePath = xmlFilePath + "index" + TASKLOG_MID + taskId + TASKLOG_EXTENSION;
		} else {
			xmlFilePath = xmlFilePath + TASKLOG_MID + taskId + TASKLOG_EXTENSION;
		}
		try {
			return new ResourceRef(pageRef.getBookRef(), Path.valueOf(xmlFilePath));
		} catch(ValidationException e) {
			throw new WrappedException(e);
		}
	}

	public static Priority getPriorityForStatus(long now, Task task, StatusResult status) {
		if(status.getDate() != null) {
			return task.getPriority(status.getDate(), now);
		} else {
			return task.getZeroDayPriority();
		}
	}

	private static void writeTasks(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Writer out,
		Cache cache,
		Page currentPage,
		long now,
		List<? extends Task> tasks,
		Map<Task,StatusResult> statuses,
		String label
	) throws ServletException, IOException, TaskException {
		int size = tasks.size();
		if(size>0) {
			HtmlRenderer htmlRenderer = HtmlRenderer.getInstance(servletContext);
			for(int i=0; i<size; i++) {
				Task task = tasks.get(i);
				final Page taskPage = task.getPage();
				StatusResult status = statuses.get(task);
				Priority priority = getPriorityForStatus(now, task, status);
				out.write("<tr>");
				if(i==0) {
					out.write("<th rowspan=\"");
					encodeTextInXhtmlAttribute(Integer.toString(size), out);
					out.write("\">");
					encodeTextInXhtml(label, out);
					out.write("</th>");
				}
				out.write("<td class=\"");
				encodeTextInXhtmlAttribute(status.getCssClass().name(), out);
				out.write("\">");
				encodeTextInXhtml(status.getDescription(), out);
				out.write("</td><td class=\"");
				encodeTextInXhtmlAttribute(priority.getCssClass(), out);
				out.write("\">");
				encodeTextInXhtml(priority.toString(), out);
				out.write("</td><td><a");
				String linkCssClass = htmlRenderer.getLinkCssClass(task);
				if(linkCssClass != null) {
					out.write(" class=\"");
					encodeTextInXhtmlAttribute(linkCssClass, out);
					out.write('"');
				}
				out.write(" href=\"");
				PageIndex pageIndex = PageIndex.getCurrentPageIndex(request);
				final PageRef taskPageRef = taskPage.getPageRef();
				Integer index = pageIndex==null ? null : pageIndex.getPageIndex(taskPageRef);
				if(index != null) {
					// view=all mode
					out.write('#');
					PageIndex.appendIdInPage(
						index,
						task.getId(),
						new MediaWriter(textInXhtmlAttributeEncoder, out)
					);
				} else if(taskPage.equals(currentPage)) {
					// Task on this page, generate anchor-only link
					encodeTextInXhtmlAttribute('#', out);
					encodeTextInXhtmlAttribute(task.getId(), out);
				} else {
					// Task on other page, generate full link
					BookRef taskBookRef = taskPageRef.getBookRef();
					encodeTextInXhtmlAttribute(
						response.encodeURL(
							com.aoindustries.net.UrlUtils.encodeUrlPath(
								request.getContextPath()
									+ taskBookRef.getPrefix()
									+ taskPageRef.getPath()
									+ '#' + task.getId(),
								response.getCharacterEncoding()
							)
						),
						out
					);
				}
				out.write("\">");
				encodeTextInXhtml(task.getLabel(), out);
				if(index != null) {
					out.write("<sup>[");
					encodeTextInXhtml(Integer.toString(index+1), out);
					out.write("]</sup>");
				}
				out.write("</a></td></tr>\n");
			}
		}
	}

	/**
	 * Make no instances.
	 */
	private TaskHtmlRenderer() {
	}
}
