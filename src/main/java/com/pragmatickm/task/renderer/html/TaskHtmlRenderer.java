/*
 * pragmatickm-task-renderer-html - Tasks rendered as HTML in a Servlet environment.
 * Copyright (C) 2013, 2014, 2015, 2016, 2017, 2019, 2020, 2021  AO Industries, Inc.
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
import com.aoindustries.exception.WrappedException;
import com.aoindustries.html.any.AnyDocument;
import com.aoindustries.html.any.AnyPalpableContent;
import com.aoindustries.html.any.AnyTABLE_c;
import com.aoindustries.html.any.AnyTBODY_c;
import com.aoindustries.html.any.AnyUnion_TBODY_THEAD_TFOOT;
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

	private static void writeRow(String header, String value, AnyUnion_TBODY_THEAD_TFOOT<?, ?> content) throws IOException {
		if(value != null) {
			content.tr__any(tr -> tr
				.th__(header)
				.td().colspan(3).__(value)
			);
		}
	}

	private static void writeRow(String header, List<?> values, AnyUnion_TBODY_THEAD_TFOOT<?, ?> content) throws IOException {
		if(values != null) {
			int size = values.size();
			if(size > 0) {
				content.tr__any(tr -> tr
					.th__(header)
					.td().colspan(3).__(td -> {
						for(int i = 0; i < size; i++) {
							if(i != 0) td.br__();
							td.text(values.get(i));
						}
					})
				);
			}
		}
	}

	private static void writeRow(String header, Calendar date, AnyUnion_TBODY_THEAD_TFOOT<?, ?> content) throws IOException {
		if(date != null) writeRow(header, CalendarUtils.formatDate(date), content);
	}

	private static void writeRow(String header, Recurring recurring, boolean relative, AnyUnion_TBODY_THEAD_TFOOT<?, ?> content) throws IOException {
		if(recurring != null) {
			writeRow(
				header,
				relative
					? (recurring.getRecurringDisplay() + " (Relative)")
					: recurring.getRecurringDisplay(),
				content
			);
		}
	}

	/**
	 * @return  When captureLevel == BODY, the tbody, which may be used to write additional content and must be passed onto
	 *          {@link #writeAfterBody(com.pragmatickm.task.model.Task, com.aoindustries.html.any.AnyTBODY_c, com.semanticcms.core.model.ElementContext)}.
	 *          For all other capture levels returns {@code null}.
	 */
	public static <
		D extends AnyDocument<D>,
		__ extends AnyPalpableContent<D, __>
	> AnyTBODY_c<D, ? extends AnyTABLE_c<D, __, ?>, ?> writeBeforeBody(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		CaptureLevel captureLevel,
		__ palpable,
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
			Map<Task, StatusResult> statuses;
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
			AnyTBODY_c<D, ? extends AnyTABLE_c<D, __, ?>, ?> tbody = palpable.table()
				.id(idAttr -> PageIndex.appendIdInPage(
					pageIndex,
					currentPage,
					task.getId(),
					idAttr
				))
				.clazz("ao-grid", "pragmatickm-task")
				.style(style)
			._c()
				.thead__any(thead -> thead
					.tr__any(tr -> tr
						.th().colspan(4).__(th -> th
							.div__(task)
						)
					)
				)
				.tbody_c();
					final long now = System.currentTimeMillis();
					writeTasks(servletContext, request, response, tbody, cache, currentPage, now, doBefores, statuses, "Do Before:");
					StatusResult status = statuses.get(task);
					tbody.tr__any(tr -> tr
						.th__("Status:")
						.td().clazz(status.getStyle().getCssClass()).colspan(3).__(status.getDescription())
					);
					String comments = status.getComments();
					if(comments != null && !comments.isEmpty()) {
						tbody.tr__any(tr -> tr
							.th__("Status Comment:")
							.td().colspan(3).__(comments)
						);
					}
					// TODO: When there are no current status comments, show any tasklog comments from the last entry
					List<TaskPriority> taskPriorities = task.getPriorities();
					for(int i_ = 0, size = taskPriorities.size(); i_ < size; i_++) {
						int i = i_;
						TaskPriority taskPriority = taskPriorities.get(i);
						tbody.tr__any(tr -> {
							if(i == 0) {
								tr.th().rowspan(size).__("Priority");
							}
							tr.td().clazz(taskPriority.getPriority().getCssClass()).colspan(3).__(taskPriority);
						});
					}
					writeRow(recurring == null ? "On:" : "Starting:", task.getOn(), tbody);
					writeRow("Recurring:", recurring, relative, tbody);
					writeRow("Assigned To:", task.getAssignedTo(), tbody);
					writeRow("Pay:", task.getPay(), tbody);
					writeRow("Cost:", task.getCost(), tbody);
					writeTasks(servletContext, request, response, tbody, cache, currentPage, now, doAfters, statuses, "Do After:");
			return tbody;
		} else {
			return null;
		}
	}

	/**
	 * @param style  ValueExpression that returns Object, only evaluated for BODY capture level
	 *
	 * @return  The tbody, which may be used to write additional content and must be passed onto
	 *          {@link #writeAfterBody(com.pragmatickm.task.model.Task, com.aoindustries.html.any.AnyTBODY_c, com.semanticcms.core.model.ElementContext)}.
	 */
	public static <
		D extends AnyDocument<D>,
		__ extends AnyPalpableContent<D, __>
	> AnyTBODY_c<D, ? extends AnyTABLE_c<D, __, ?>, ?> writeBeforeBody(
		ServletContext servletContext,
		ELContext elContext,
		HttpServletRequest request,
		HttpServletResponse response,
		CaptureLevel captureLevel,
		__ palpable,
		Task task,
		ValueExpression style
	) throws TaskException, IOException, ServletException {
		return writeBeforeBody(
			servletContext,
			request,
			response,
			captureLevel,
			palpable,
			task,
			captureLevel == CaptureLevel.BODY ? resolveValue(style, Object.class, elContext) : null
		);
	}

	public static void writeAfterBody(Task task, AnyTBODY_c<?, ? extends AnyTABLE_c<?, ?, ?>, ?> tbody, ElementContext context) throws IOException {
				BufferResult body = task.getBody();
				if(body.getLength() > 0) {
					tbody.tr__any(tr -> tr
						.td().colspan(4).__(td ->
							body.writeTo(new NodeBodyWriter(task, td.getDocument().getUnsafe(), context))
						)
					);
				}
				tbody
			.__()
		.__();
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
		AnyUnion_TBODY_THEAD_TFOOT<?, ?> content,
		Cache cache, // TODO: Unused
		Page currentPage,
		long now,
		List<? extends Task> tasks,
		Map<Task, StatusResult> statuses,
		String label
	) throws ServletException, IOException, TaskException {
		int size = tasks.size();
		if(size > 0) {
			HtmlRenderer htmlRenderer = HtmlRenderer.getInstance(servletContext);
			for(int i_ = 0; i_ < size; i_++) {
				int i = i_;
				Task task = tasks.get(i);
				final Page taskPage = task.getPage();
				StatusResult status = statuses.get(task);
				Priority priority = getPriorityForStatus(now, task, status);
				content.tr__any(tr -> {
					if(i == 0) {
						tr.th().rowspan(size).__(label);
					}
					tr.td().clazz(status.getStyle().getCssClass()).__(status.getDescription())
					.td().clazz(priority.getCssClass()).__(priority)
					.td__any(td -> {
						PageIndex pageIndex = PageIndex.getCurrentPageIndex(request);
						final PageRef taskPageRef = taskPage.getPageRef();
						Integer index = pageIndex==null ? null : pageIndex.getPageIndex(taskPageRef);
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
						td.a()
							.clazz(htmlRenderer.getLinkCssClass(task))
							.href(response.encodeURL(href.toString()))
						.__(a -> {
							a.text(task);
							if(index != null) {
								a.sup__any(sup -> sup.text('[').text(Integer.toString(index+1)).text(']'));
							}
						});
					});
				});
			}
		}
	}

	/**
	 * Make no instances.
	 */
	private TaskHtmlRenderer() {
	}
}
