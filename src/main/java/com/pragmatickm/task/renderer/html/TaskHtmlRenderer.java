/*
 * pragmatickm-task-renderer-html - Tasks rendered as HTML in a Servlet environment.
 * Copyright (C) 2013, 2014, 2015, 2016, 2017, 2019, 2020  AO Industries, Inc.
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

import com.aoindustries.collections.AoCollections;
import com.aoindustries.encoding.Coercion;
import com.aoindustries.encoding.MediaWriter;
import com.aoindustries.encoding.TextInXhtmlAttributeEncoder;
import static com.aoindustries.encoding.TextInXhtmlAttributeEncoder.encodeTextInXhtmlAttribute;
import static com.aoindustries.encoding.TextInXhtmlAttributeEncoder.textInXhtmlAttributeEncoder;
import com.aoindustries.exception.WrappedException;
import com.aoindustries.html.Html;
import com.aoindustries.io.buffer.BufferResult;
import com.aoindustries.net.Path;
import com.aoindustries.net.URIEncoder;
import static com.aoindustries.taglib.AttributeUtils.resolveValue;
import com.aoindustries.util.CalendarUtils;
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
import java.util.ArrayList;
import java.util.Calendar;
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

	// TODO: Unused: private static final String REMOVE_JSP_EXTENSION = ".jsp";
	// TODO: Unused: private static final String REMOVE_JSPX_EXTENSION = ".jspx";
	private static final String TASKLOG_MID = "-tasklog-";
	private static final String TASKLOG_EXTENSION = ".xml";

	private static void writeRow(String header, String value, Html html) throws IOException {
		if(value != null) {
			html.out.write("<tr><th>");
			html.text(header);
			html.out.write("</th><td colspan=\"3\">");
			html.text(value);
			html.out.write("</td></tr>\n");
		}
	}

	private static void writeRow(String header, List<?> values, Html html) throws IOException {
		if(values != null) {
			int size = values.size();
			if(size > 0) {
				html.out.write("<tr><th>");
				html.text(header);
				html.out.write("</th><td colspan=\"3\">");
				for(int i=0; i<size; i++) {
					html.text(values.get(i));
					if(i != (size - 1)) {
						html.br__();
					}
				}
				html.out.write("</td></tr>\n");
			}
		}
	}

	private static void writeRow(String header, Calendar date, Html html) throws IOException {
		if(date != null) writeRow(header, CalendarUtils.formatDate(date), html);
	}

	private static void writeRow(String header, Recurring recurring, boolean relative, Html html) throws IOException {
		if(recurring != null) {
			writeRow(
				header,
				relative
					? (recurring.getRecurringDisplay() + " (Relative)")
					: recurring.getRecurringDisplay(),
				html
			);
		}
	}

	public static void writeBeforeBody(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		CaptureLevel captureLevel,
		Html html,
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
				doBefores = new ArrayList<>(size);
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
				Set<Task> allTasks = AoCollections.newHashSet(
					doBefores.size()
					+ 1 // this task
					+ doAfters.size()
				);
				allTasks.addAll(doBefores);
				allTasks.add(task);
				allTasks.addAll(doAfters);
				statuses = TaskUtil.getMultipleStatuses(servletContext, request, response, allTasks, cache);
			}
			// Write the task itself to this page
			final PageIndex pageIndex = PageIndex.getCurrentPageIndex(request);
			html.out.write("<table id=\"");
			PageIndex.appendIdInPage(
				pageIndex,
				currentPage,
				task.getId(),
				new MediaWriter(html.encodingContext, TextInXhtmlAttributeEncoder.textInXhtmlAttributeEncoder, html.out)
			);
			html.out.write("\" class=\"ao-grid pragmatickm-task\"");
			style = Coercion.nullIfEmpty(style); // TODO: trimNullIfEmpty, here and all (class, too, remove more)
			if(style != null) {
				html.out.write(" style=\"");
				Coercion.write(style, textInXhtmlAttributeEncoder, html.out);
				html.out.write('"');
			}
			html.out.write(">\n"
					+ "<thead><tr><th colspan=\"4\"><div>");
			html.text(task.getLabel());
			html.out.write("</div></th></tr></thead>\n"
					+ "<tbody>\n");
			final long now = System.currentTimeMillis();
			writeTasks(servletContext, request, response, html, cache, currentPage, now, doBefores, statuses, "Do Before:");
			html.out.write("<tr><th>Status:</th><td class=\"");
			StatusResult status = statuses.get(task);
			encodeTextInXhtmlAttribute(status.getStyle().getCssClass(), html.out);
			html.out.write("\" colspan=\"3\">");
			html.text(status.getDescription());
			html.out.write("</td></tr>\n");
			String comments = status.getComments();
			if(comments != null && !comments.isEmpty()) {
				html.out.write("<tr><th>Status Comment:</th><td colspan=\"3\">");
				html.text(comments);
				html.out.write("</td></tr>\n");
			}
			List<TaskPriority> taskPriorities = task.getPriorities();
			for(int i=0, size=taskPriorities.size(); i<size; i++) {
				TaskPriority taskPriority = taskPriorities.get(i);
				html.out.write("<tr>");
				if(i==0) {
					html.out.write("<th");
					if(size != 1) {
						html.out.write(" rowspan=\"");
						encodeTextInXhtmlAttribute(Integer.toString(size), html.out);
						html.out.write('"');
					}
					html.out.write(">Priority:</th>");
				}
				html.out.write("<td class=\"");
				Priority priority = taskPriority.getPriority();
				encodeTextInXhtmlAttribute(priority.getCssClass(), html.out);
				html.out.write("\" colspan=\"3\">");
				html.text(taskPriority);
				html.out.write("</td></tr>\n");
			}
			writeRow(recurring==null ? "On:" : "Starting:", task.getOn(), html);
			writeRow("Recurring:", recurring, relative, html);
			writeRow("Assigned To:", task.getAssignedTo(), html);
			writeRow("Pay:", task.getPay(), html);
			writeRow("Cost:", task.getCost(), html);
			writeTasks(servletContext, request, response, html, cache, currentPage, now, doAfters, statuses, "Do After:");
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
		Html html,
		Task task,
		ValueExpression style
	) throws TaskException, IOException, ServletException {
		writeBeforeBody(
			servletContext,
			request,
			response,
			captureLevel,
			html,
			task,
			captureLevel == CaptureLevel.BODY ? resolveValue(style, Object.class, elContext) : null
		);
	}

	public static void writeAfterBody(Task task, Html html, ElementContext context) throws IOException {
		BufferResult body = task.getBody();
		if(body.getLength() > 0) {
			html.out.write("<tr><td colspan=\"4\">\n");
			body.writeTo(new NodeBodyWriter(task, html.out, context));
			html.out.write("\n</td></tr>\n");
		}
		html.out.write("</tbody>\n"
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
		Html html,
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
				html.out.write("<tr>");
				if(i==0) {
					html.out.write("<th rowspan=\"");
					encodeTextInXhtmlAttribute(Integer.toString(size), html.out);
					html.out.write("\">");
					html.text(label);
					html.out.write("</th>");
				}
				html.out.write("<td class=\"");
				encodeTextInXhtmlAttribute(status.getStyle().getCssClass(), html.out);
				html.out.write("\">");
				html.text(status.getDescription());
				html.out.write("</td><td class=\"");
				encodeTextInXhtmlAttribute(priority.getCssClass(), html.out);
				html.out.write("\">");
				html.text(priority);
				html.out.write("</td><td><a");
				String linkCssClass = htmlRenderer.getLinkCssClass(task);
				if(linkCssClass != null) {
					html.out.write(" class=\"");
					encodeTextInXhtmlAttribute(linkCssClass, html.out);
					html.out.write('"');
				}
				PageIndex pageIndex = PageIndex.getCurrentPageIndex(request);
				final PageRef taskPageRef = taskPage.getPageRef();
				Integer index = pageIndex==null ? null : pageIndex.getPageIndex(taskPageRef);
				html.out.write(" href=\"");
				StringBuilder href = new StringBuilder();
				if(index != null) {
					// view=all mode
					href.append('#');
					URIEncoder.encodeURIComponent(
						PageIndex.getRefId(
							index,
							task.getId()
						),
						href
					);
				} else if(taskPage.equals(currentPage)) {
					// Task on this page, generate anchor-only link
					href.append('#');
					URIEncoder.encodeURIComponent(task.getId(), href);
				} else {
					// Task on other page, generate full link
					BookRef taskBookRef = taskPageRef.getBookRef();
					URIEncoder.encodeURI(request.getContextPath(), href);
					URIEncoder.encodeURI(taskBookRef.getPrefix(), href);
					URIEncoder.encodeURI(taskPageRef.getPath().toString(), href);
					href.append('#');
					URIEncoder.encodeURIComponent(task.getId(), href);
				}
				encodeTextInXhtmlAttribute(
					response.encodeURL(
						href.toString()
					),
					html.out
				);
				html.out.write("\">");
				html.text(task.getLabel());
				if(index != null) {
					html.out.write("<sup>[");
					html.text(index + 1);
					html.out.write("]</sup>");
				}
				html.out.write("</a></td></tr>\n");
			}
		}
	}

	/**
	 * Make no instances.
	 */
	private TaskHtmlRenderer() {
	}
}
