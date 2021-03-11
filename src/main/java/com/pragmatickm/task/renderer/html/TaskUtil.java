/*
 * pragmatickm-task-renderer-html - Tasks rendered as HTML in a Servlet environment.
 * Copyright (C) 2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
import com.aoindustries.lang.Strings;
import com.aoindustries.net.DomainName;
import com.aoindustries.net.Path;
import com.aoindustries.servlet.subrequest.HttpServletSubRequest;
import com.aoindustries.servlet.subrequest.HttpServletSubResponse;
import com.aoindustries.servlet.subrequest.UnmodifiableCopyHttpServletRequest;
import com.aoindustries.servlet.subrequest.UnmodifiableCopyHttpServletResponse;
import com.aoindustries.tempfiles.TempFileContext;
import com.aoindustries.tempfiles.servlet.TempFileContextEE;
import com.aoindustries.util.CalendarUtils;
import com.aoindustries.util.Tuple2;
import com.aoindustries.util.UnmodifiableCalendar;
import com.aoindustries.util.concurrent.ExecutionExceptions;
import com.aoindustries.util.schedule.Recurring;
import com.pragmatickm.task.model.Priority;
import com.pragmatickm.task.model.Task;
import com.pragmatickm.task.model.TaskAssignment;
import com.pragmatickm.task.model.TaskException;
import com.pragmatickm.task.model.TaskLog;
import com.pragmatickm.task.model.User;
import com.semanticcms.core.controller.Book;
import com.semanticcms.core.controller.Cache;
import com.semanticcms.core.controller.CacheFilter;
import com.semanticcms.core.controller.CapturePage;
import com.semanticcms.core.controller.ConcurrencyCoordinator;
import com.semanticcms.core.controller.PageRefResolver;
import com.semanticcms.core.controller.SemanticCMS;
import com.semanticcms.core.model.BookRef;
import com.semanticcms.core.model.Element;
import com.semanticcms.core.model.ElementRef;
import com.semanticcms.core.model.Page;
import com.semanticcms.core.model.PageRef;
import com.semanticcms.core.model.ResourceRef;
import com.semanticcms.core.pages.CaptureLevel;
import com.semanticcms.core.resources.ResourceStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

final public class TaskUtil {

	public static TaskLog getTaskLogInDomain(
		ServletContext servletContext,
		HttpServletRequest request,
		DomainName domain,
		Path book,
		String page,
		String taskId
	) throws ServletException, IOException {
		PageRef pageRef = PageRefResolver.getPageRef(
			servletContext,
			request,
			domain,
			book,
			page
		);
		// Book must be accessible
		ResourceRef xmlFile = TaskHtmlRenderer.getTaskLogXmlFile(pageRef, taskId);
		BookRef bookRef = xmlFile.getBookRef();
		Book bookObj = SemanticCMS.getInstance(servletContext).getBook(bookRef);
		if(!bookObj.isAccessible()) {
			throw new IllegalArgumentException("Book is not accessible: " + xmlFile);
		}
		ResourceStore resourceStore = bookObj.getResources();
		if(!resourceStore.isAvailable()) {
			throw new IllegalArgumentException("Resource store is not available: " + bookRef);
		}
		return TaskLog.getTaskLog(
			resourceStore,
			xmlFile
		);
	}

	/**
	 * Gets the task log for the given book, page, and id within the domain of the current page.
	 */
	public static TaskLog getTaskLogInBook(
		ServletContext servletContext,
		HttpServletRequest request,
		Path book,
		String page,
		String taskId
	) throws ServletException, IOException {
		return getTaskLogInDomain(servletContext, request, null, book, page, taskId);
	}

	/**
	 * Gets the task log for the given page and id within the book of the current page.
	 */
	public static TaskLog getTaskLog(
		ServletContext servletContext,
		HttpServletRequest request,
		String page,
		String taskId
	) throws ServletException, IOException {
		return getTaskLogInDomain(servletContext, request, null, null, page, taskId);
	}

	public static TaskLog.Entry getMostRecentEntry(TaskLog taskLog, String statuses) throws IOException {
		String[] trimmed;
		int size;
		{
			List<String> split = Strings.split(statuses, ','); // Split on comma only, because of "Nothing To Do" status having spaces
			size = split.size();
			trimmed = new String[size];
			for(int i = 0; i < size; i++) {
				trimmed[i] = split.get(i).trim();
			}
		}
		List<TaskLog.Entry> entries = taskLog.getEntries();
		for(int i = entries.size() - 1; i >= 0; i--) {
			TaskLog.Entry entry = entries.get(i);
			String label = entry.getStatus().getLabel();
			for(int j = 0; j < size; j++) {
				if(label.equalsIgnoreCase(trimmed[j])) {
					return entry;
				}
			}
		}
		return null;
	}

	private static final String GET_STATUS_CACHE_KEY = TaskUtil.class.getName() + ".getStatus";

	@SuppressWarnings("unchecked")
	private static Map<Task, StatusResult> getStatusCache(Cache cache) {
		return cache.getAttribute(
			GET_STATUS_CACHE_KEY,
			Map.class,
			() -> cache.newMap()
		);
	}

	/**
	 * <p>
	 * Gets a human-readable description of the task status as well as an associated class.
	 * The status of a task, without any specific qualifying date, is:
	 * </p>
	 * <p>
	 * For non-scheduled tasks (with no "on" date and no "recurring"), the status is:
	 * </p>
	 * <ol>
	 *   <li>The status of the most recent log entry with no "scheduledOn" value</li>
	 *   <li>"New"</li>
	 * </ol>
	 * <p>
	 * For a scheduled, non-recurring task, the status is:
	 * </p>
	 * <ol>
	 *   <li>If the status of the most recent log entry with a "scheduledOn" value equaling the task "on" date is of a "completedSchedule" type - use the status.</li>
	 *   <li>If in the past, "Late YYYY-MM-DD"</li>
	 *   <li>If today, "Due Today"</li>
	 *   <li>If there is a status of the most recent log entry with a "scheduledOn" value equaling the task "on" date - use the status.</li>
	 *   <li>Is in the future, "Waiting until YYYY-MM-DD"</li>
	 * </ol>
	 * <p>
	 * For a recurring task, the status is:
	 * </p>
	 * <ol>
	 *   <li>Find the first incomplete scheduledOn date (based on most recent log entries per scheduled date, in time order</li>
	 *   <li>If the first incomplete is in the past, "Late YYYY-MM-DD"</li>
	 *   <li>If the first incomplete is today, "Due Today"</li>
	 *   <li>If the first incomplete is in the future, "Waiting until YYYY-MM-DD"</li>
	 * </ol>
	 * <p>
	 * Status only available once frozen.
	 * </p>
	 */
	public static StatusResult getStatus(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Task task
	) throws TaskException, ServletException, IOException {
		return getStatus(
			servletContext,
			request,
			response,
			task,
			CacheFilter.getCache(request)
		);
	}

	public static StatusResult getStatus(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Task task,
		Cache cache
	) throws TaskException, ServletException, IOException {
		return getStatus(
			servletContext,
			request,
			response,
			task,
			cache,
			getStatusCache(cache)
		);
	}

	private static StatusResult getStatus(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Task task,
		Cache cache,
		Map<Task, StatusResult> statusCache
	) throws TaskException, ServletException, IOException {
		StatusResult sr = statusCache.get(task);
		if(sr == null) {
			// TODO: Concurrency limiter here?
			sr = doGetStatus(servletContext, request, response, task, cache, statusCache);
			statusCache.put(task, sr);
		}
		return sr;
	}

	private static StatusResult doGetStatus(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Task task,
		Cache cache,
		Map<Task, StatusResult> statusCache
	) throws TaskException, ServletException, IOException {
		UnmodifiableCalendar on = task.getOn();
		Recurring recurring = task.getRecurring();
		boolean relative = task.getRelative();
		// Check if all dependencies are completed
		boolean allDoBeforesCompleted = true;
		// TODO: Concurrent getDoBefores?
		for(ElementRef doBeforeRef : task.getDoBefores()) {
			Page capturedPage = CapturePage.capturePage(
				servletContext,
				request,
				response,
				doBeforeRef.getPageRef(),
				CaptureLevel.META,
				cache
			);
			String taskId = doBeforeRef.getId();
			Element elem = capturedPage.getElementsById().get(taskId);
			if(elem == null) throw new TaskException("doBefore not found: " + doBeforeRef);
			if(!(elem instanceof Task)) throw new TaskException("doBefore is not a task: " + elem.getClass().getName());
			if(capturedPage.getGeneratedIds().contains(taskId)) throw new TaskException("Not allowed to reference task by generated id, set an explicit id on the task: " + elem);
			Task doBefore = (Task)elem;
			StatusResult doBeforeStatus = getStatus(
				servletContext,
				request,
				response,
				doBefore,
				cache,
				statusCache
			);
			if(!doBeforeStatus.isCompletedSchedule()) {
				allDoBeforesCompleted = false;
				break;
			}
		}
		final GregorianCalendar today = CalendarUtils.getToday();
		final long todayMillis = today.getTimeInMillis();
		TaskLog taskLog = task.getTaskLog();
		if(on==null && recurring==null) {
			// Non-scheduled task
			TaskLog.Entry entry = taskLog.getMostRecentEntry(null);
			if(entry != null) {
				TaskLog.Status entryStatus = entry.getStatus();
				if(entryStatus==TaskLog.Status.PROGRESS) {
					// If marked with "Progress" on or after today, will be moved to the future list
					long entryOnMillis = entry.getOn().getTimeInMillis();
					boolean future = entryOnMillis >= todayMillis;
					return new StatusResult(
						StatusResult.Style.getStyle(TaskLog.Status.PROGRESS),
						entryOnMillis == todayMillis
							? "Progress Today"
							: ("Progress on " + CalendarUtils.formatDate(entry.getOn())),
						entry.getComments(),
						false,
						!future && allDoBeforesCompleted,
						future,
						null
					);
				} else {
					return new StatusResult(
						entryStatus,
						entry.getComments(),
						allDoBeforesCompleted,
						false,
						null
					);
				}
			}
			if(allDoBeforesCompleted) {
				return new StatusResult(
					StatusResult.Style.NEW,
					"New",
					null,
					false,
					true,
					false,
					null
				);
			} else {
				return new StatusResult(
					StatusResult.Style.NEW_WAITING_DO_AFTER,
					"New waiting for \"Do Before\"",
					null,
					false,
					false,
					false,
					null
				);
			}
		} else if(on!=null && recurring==null) {
			// Scheduled, non-recurring task
			TaskLog.Entry entry = taskLog.getMostRecentEntry(on);
			TaskLog.Status entryStatus = entry==null ? null : entry.getStatus();
			if(entryStatus != null) {
				assert entry != null;
				if(entryStatus.isCompletedSchedule()) {
					return new StatusResult(
						entryStatus,
						entry.getComments(),
						allDoBeforesCompleted,
						false,
						on
					);
				} else if(entryStatus==TaskLog.Status.PROGRESS) {
					long entryOnMillis = entry.getOn().getTimeInMillis();
					if(entryOnMillis >= todayMillis) {
						// If marked with "Progress" on or after today, will be moved to the future list
						return new StatusResult(
							StatusResult.Style.getStyle(TaskLog.Status.PROGRESS),
							entryOnMillis == todayMillis
								? "Progress Today"
								: ("Progress on " + CalendarUtils.formatDate(entry.getOn())),
							entry.getComments(),
							false,
							false,
							true,
							on
						);
					}
				}
			}
			// Past
			if(on.before(today)) {
				if(allDoBeforesCompleted) {
					return new StatusResult(
						StatusResult.Style.LATE,
						"Late " + CalendarUtils.formatDate(on),
						entry!=null ? entry.getComments() : null,
						false,
						true,
						false,
						on
					);
				} else {
					return new StatusResult(
						StatusResult.Style.LATE_WAITING_DO_AFTER,
						"Late " + CalendarUtils.formatDate(on) + " waiting for \"Do Before\"",
						entry!=null ? entry.getComments() : null,
						false,
						false,
						false,
						on
					);
				}
			}
			// Present
			if(on.getTimeInMillis() == todayMillis) {
				if(allDoBeforesCompleted) {
					return new StatusResult(
						StatusResult.Style.DUE_TODAY,
						"Due Today",
						entry!=null ? entry.getComments() : null,
						false,
						true,
						false,
						on
					);
				} else {
					return new StatusResult(
						StatusResult.Style.DUE_TODAY_WAITING_DO_AFTER,
						"Due Today waiting for \"Do Before\"",
						entry!=null ? entry.getComments() : null,
						false,
						false,
						false,
						on
					);
				}
			}
			// Future
			if(entryStatus != null) {
				assert entry != null;
				return new StatusResult(
					entryStatus,
					entry.getComments(),
					allDoBeforesCompleted,
					!entryStatus.isCompletedSchedule(),
					on
				);
			}
			return new StatusResult(
				StatusResult.Style.IN_FUTURE,
				"Waiting until " + CalendarUtils.formatDate(on),
				null,
				false, // Was true, but if never done and waiting for future, it isn't completed
				false,
				true,
				on
			);
		} else {
			// Recurring task (possibly with null "on" date)
			final Calendar firstIncomplete;
			if(relative) {
				// TODO: When "on" is set today because new task never completed, the status
				//       should become the highest priority, and the assignments should be to
				//       all assigned.  In other words: start most severe instead of least, because
				//       tasks are remaining unfinished due to perpetual "Low" initial priority.

				// Will use "on" or today if no completed tasklog entry
				Calendar recurringFrom = (on != null) ? on : today;
				// Schedule from most recent completed tasklog entry
				List<TaskLog.Entry> entries = taskLog.getEntries();
				for(int i=entries.size()-1; i>=0; i--) {
					TaskLog.Entry entry = entries.get(i);
					if(entry.getStatus().isCompletedSchedule()) {
						Calendar completedOn = entry.getOn();
						SortedSet<? extends Calendar> scheduledOns = entry.getScheduledOns();
						//String checkResult = recurring.checkScheduleFrom(completedOn, "relative");
						//if(checkResult != null) throw new TaskException(checkResult);
						Iterator<Calendar> recurringIter = recurring.getScheduleIterator(completedOn);
						// Find the first date that is after both the completedOn and scheduledOn
						do {
							recurringFrom = recurringIter.next();
						} while(
							recurringFrom.getTimeInMillis() <= completedOn.getTimeInMillis()
							|| (!scheduledOns.isEmpty() && recurringFrom.getTimeInMillis() <= scheduledOns.last().getTimeInMillis())
						);
						break;
					}
				}
				// If "on" is after the determined recurringFrom, use "on"
				if(on != null && on.getTimeInMillis() > recurringFrom.getTimeInMillis()) {
					recurringFrom = on;
				}
				firstIncomplete = recurringFrom;
			} else {
				if(on == null) throw new TaskException("\"on\" date must be provided for non-relative recurring tasks");
				firstIncomplete = taskLog.getFirstIncompleteScheduledOn(on, recurring);
			}
			if(firstIncomplete.before(today)) {
				TaskLog.Entry entry = taskLog.getMostRecentEntry(firstIncomplete);
				if(entry!=null) {
					TaskLog.Status entryStatus = entry.getStatus();
					if(entryStatus == TaskLog.Status.PROGRESS) {
						long entryOnMillis = entry.getOn().getTimeInMillis();
						if(entryOnMillis >= todayMillis) {
							// If marked with "Progress" on or after today, will be moved to the future list
							return new StatusResult(
								StatusResult.Style.getStyle(TaskLog.Status.PROGRESS),
								entryOnMillis == todayMillis
									? "Progress Today"
									: ("Progress on " + CalendarUtils.formatDate(entry.getOn())),
								entry.getComments(),
								false,
								false,
								true,
								firstIncomplete
							);
						}
					}
				}
				if(allDoBeforesCompleted) {
					return new StatusResult(
						StatusResult.Style.LATE,
						"Late " + CalendarUtils.formatDate(firstIncomplete),
						entry!=null ? entry.getComments() : null,
						false,
						true,
						false,
						firstIncomplete
					);
				} else {
					return new StatusResult(
						StatusResult.Style.LATE_WAITING_DO_AFTER,
						"Late " + CalendarUtils.formatDate(firstIncomplete) + " waiting for \"Do Before\"",
						entry!=null ? entry.getComments() : null,
						false,
						false,
						false,
						firstIncomplete
					);
				}
			}
			if(firstIncomplete.getTimeInMillis() == todayMillis) {
				TaskLog.Entry entry = taskLog.getMostRecentEntry(firstIncomplete);
				if(entry!=null) {
					TaskLog.Status entryStatus = entry.getStatus();
					if(entryStatus == TaskLog.Status.PROGRESS) {
						long entryOnMillis = entry.getOn().getTimeInMillis();
						if(entryOnMillis >= todayMillis) {
							// If marked with "Progress" on or after today, will be moved to the future list
							return new StatusResult(
								StatusResult.Style.getStyle(TaskLog.Status.PROGRESS),
								entryOnMillis == todayMillis
									? "Progress Today"
									: ("Progress on " + CalendarUtils.formatDate(entry.getOn())),
								entry.getComments(),
								false,
								false,
								true,
								firstIncomplete
							);
						}
					}
				}
				if(allDoBeforesCompleted) {
					return new StatusResult(
						StatusResult.Style.DUE_TODAY,
						"Due Today",
						entry!=null ? entry.getComments() : null,
						false,
						true,
						false,
						firstIncomplete
					);
				} else {
					return new StatusResult(
						StatusResult.Style.DUE_TODAY_WAITING_DO_AFTER,
						"Due Today waiting for \"Do Before\"",
						entry!=null ? entry.getComments() : null,
						false,
						false,
						false,
						firstIncomplete
					);
				}
			}
			return new StatusResult(
				StatusResult.Style.IN_FUTURE,
				"Waiting until " + CalendarUtils.formatDate(firstIncomplete),
				null,
				true,
				false,
				true,
				firstIncomplete
			);
		}
	}

	public static Map<Task, StatusResult> getMultipleStatuses(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Collection<? extends Task> tasks
	) throws TaskException, ServletException, IOException {
		return getMultipleStatuses(
			servletContext,
			request,
			response,
			tasks,
			CacheFilter.getCache(request)
		);
	}

	public static Map<Task, StatusResult> getMultipleStatuses(
		final ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Collection<? extends Task> tasks,
		final Cache cache
	) throws TaskException, ServletException, IOException {
		int size = tasks.size();
		if(size == 0) {
			return Collections.emptyMap();
		} else {
			final Map<Task, StatusResult> statusCache = getStatusCache(cache);
			if(size == 1) {
				Task task = tasks.iterator().next();
				return Collections.singletonMap(
					task,
					getStatus(
						servletContext,
						request,
						response,
						task,
						cache,
						statusCache
					)
				);
			} else {
				Map<Task, StatusResult> results = AoCollections.newLinkedHashMap(size);
				List<Task> notCached = null; // Created when first needed
				for(Task task : tasks) {
					StatusResult cached = statusCache.get(task);
					// Add entry even if null to set ordering, replacing this value later will not alter order
					results.put(task, cached);
					if(cached == null) {
						if(notCached == null) notCached = new ArrayList<>(size - results.size());
						notCached.add(task);
					}
				}
				if(notCached != null) {
					int notCachedSize = notCached.size();
					assert notCachedSize > 0;
					if(
						notCachedSize > 1
						&& ConcurrencyCoordinator.useConcurrentSubrequests(request)
					) {
						//System.err.println("notCachedSize = " + notCachedSize + ", doing concurrent getStatus");
						// Concurrent implementation
						List<Callable<StatusResult>> concurrentTasks = new ArrayList<>(notCachedSize);
						{
							final HttpServletRequest threadSafeReq = new UnmodifiableCopyHttpServletRequest(request);
							final HttpServletResponse threadSafeResp = new UnmodifiableCopyHttpServletResponse(response);
							final TempFileContext tempFileContext = TempFileContextEE.get(request);
							for(final Task task : notCached) {
								concurrentTasks.add((Callable<StatusResult>) () -> {
									HttpServletRequest subrequest = new HttpServletSubRequest(threadSafeReq);
									HttpServletResponse subresponse = new HttpServletSubResponse(threadSafeResp, tempFileContext);
									return getStatus(
										servletContext,
										subrequest,
										subresponse,
										task,
										cache,
										statusCache
									);
								});
							}
						}
						List<StatusResult> concurrentResults;
						try {
							concurrentResults = SemanticCMS.getInstance(servletContext).getExecutors().getPerProcessor().callAll(concurrentTasks);
						} catch(InterruptedException e) {
							throw new ServletException(e);
						} catch(ExecutionException e) {
							// Maintain expected exception types while not losing stack trace
							// TODO: Once pragmatickm-task-model is SNAPSHOT again: ExecutionExceptions.wrapAndThrow(e, TaskException.class, TaskException::new);
							// TODO: Compatibility implementation using initCause:
							ExecutionExceptions.wrapAndThrow(e, TaskException.class,
								(message, ee) -> {
									TaskException te = new TaskException(message);
									te.initCause(ee);
									return te;
								}
							);
							ExecutionExceptions.wrapAndThrow(e, IOException.class, IOException::new);
							throw new ServletException(e);
						}
						for(int i=0; i<notCachedSize; i++) {
							results.put(
								notCached.get(i),
								concurrentResults.get(i)
							);
						}
					} else {
						// Sequential implementation
						for(Task task : notCached) {
							results.put(
								task,
								getStatus(
									servletContext,
									request,
									response,
									task,
									cache,
									statusCache
								)
							);
						}
					}
				}
				assert results.size() == size;
				return Collections.unmodifiableMap(results);
			}
		}
	}

	/**
	 * Finds all tasks that must be done after this task.
	 * This requires a capture of the entire page tree
	 * meta data to find any task that has a doBefore pointing to this task.
	 */
	public static List<Task> getDoAfters(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Task task
	) throws ServletException, IOException {
		final String taskId = task.getId();
		final Page taskPage = task.getPage();
		final List<Task> doAfters = new ArrayList<>();
		final SemanticCMS semanticCMS = SemanticCMS.getInstance(servletContext);
		CapturePage.traversePagesDepthFirst(
			servletContext,
			request,
			response,
			semanticCMS.getRootBook().getContentRoot(),
			CaptureLevel.META,
			(Page page, int depth) -> {
				for(Element element : page.getElements()) {
					if(element instanceof Task) {
						Task pageTask = (Task)element;
						for(ElementRef doBefore : pageTask.getDoBefores()) {
							if(
								doBefore.getPageRef().equals(taskPage.getPageRef())
								&& doBefore.getId().equals(taskId)
							) {
								doAfters.add(pageTask);
							}
						}
					}
				}
				return null;
			},
			(Page page) -> page.getChildRefs(),
			(PageRef childPage) -> semanticCMS.getBook(childPage.getBookRef()).isAccessible(),
			null
		);
		return Collections.unmodifiableList(doAfters);
	}

	/**
	 * Finds all tasks that must be done after each of the provided tasks.
	 * This requires a capture of the entire page tree meta data
	 * to find any task that has a doBefore pointing to each task.
	 *
	 * @return  The map of doAfters, in the same iteration order as the provided
	 *          tasks.  If no doAfters for a given task, will contain an empty list.
	 */
	public static Map<Task, List<Task>> getMultipleDoAfters(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Collection<? extends Task> tasks
	) throws ServletException, IOException {
		int size = tasks.size();
		if(size == 0) {
			return Collections.emptyMap();
		} else if(size == 1) {
			Task task = tasks.iterator().next();
			return Collections.singletonMap(
				task,
				getDoAfters(servletContext, request, response, task)
			);
		} else {
			// Fill with empty lists, this sets the iteration order, too
			final Map<Task, List<Task>> results = AoCollections.newLinkedHashMap(size);
			// Build map from ElementRef back to Task, for fast lookup during traversal
			final Map<ElementRef, Task> tasksByElementRef = AoCollections.newHashMap(size);
			{
				List<Task> emptyList = Collections.emptyList();
				for(Task task : tasks) {
					if(results.put(task, emptyList) != null) {
						throw new AssertionError();
					}
					if(tasksByElementRef.put(task.getElementRef(), task) != null) {
						throw new AssertionError();
					}
				}
			}
			final SemanticCMS semanticCMS = SemanticCMS.getInstance(servletContext);
			CapturePage.traversePagesDepthFirst(
				servletContext,
				request,
				response,
				semanticCMS.getRootBook().getContentRoot(),
				CaptureLevel.META,
				(Page page, int depth) -> {
					try {
						for(Element element : page.getElements()) {
							if(element instanceof Task) {
								Task pageTask = (Task)element;
								for(ElementRef doBeforeRef : pageTask.getDoBefores()) {
									Task doBefore = tasksByElementRef.get(doBeforeRef);
									if(doBefore != null) {
										if(doBefore.getPage().getGeneratedIds().contains(doBefore.getId())) throw new TaskException("Not allowed to reference task by generated id, set an explicit id on the task: " + doBefore);
										List<Task> doAfters = results.get(doBefore);
										int doAftersSize = doAfters.size();
										if(doAftersSize == 0) {
											results.put(doBefore, Collections.singletonList(pageTask));
										} else {
											if(doAftersSize == 1) {
												Task first = doAfters.get(0);
												doAfters = new ArrayList<>();
												doAfters.add(first);
												results.put(doBefore, doAfters);
											}
											doAfters.add(pageTask);
										}
									}
								}
							}
						}
						return null;
					} catch(TaskException e) {
						throw new ServletException(e);
					}
				},
				(Page page) -> page.getChildRefs(),
				(PageRef childPage) -> semanticCMS.getBook(childPage.getBookRef()).isAccessible(),
				null
			);
			// Wrap any with size of 2 or more with unmodifiable, 0 and 1 already are unmodifiable
			for(Map.Entry<Task, List<Task>> entry : results.entrySet()) {
				List<Task> doAfters = entry.getValue();
				if(doAfters.size() > 1) entry.setValue(Collections.unmodifiableList(doAfters));
			}
			// Make entire map unmodifiable
			return Collections.unmodifiableMap(results);
		}
	}

	public static User getUser(
		HttpServletRequest request,
		HttpServletResponse response
	) {
		String userParam = request.getParameter("user");
		if(userParam != null) {
			// Find and set cookie
			User user = userParam.isEmpty() ? null : User.valueOf(userParam);
			Cookies.setUser(request, response, user);
			return user;
		} else {
			// Get from cookie
			return Cookies.getUser(request);
		}
	}

	public static Set<User> getAllUsers() {
		return EnumSet.allOf(User.class);
	}

	private static Priority getEffectivePriority(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Cache cache,
		Map<Task, StatusResult> statusCache,
		long now,
		Task task,
		StatusResult status,
		Map<Task, List<Task>> doAftersByTask,
		Map<Task, Priority> effectivePriorities
	) throws TaskException, ServletException, IOException {
		Priority cached = effectivePriorities.get(task);
		if(cached != null) return cached;
		// Find the maximum priority of this task and all that will be done after it
		Priority effective = TaskHtmlRenderer.getPriorityForStatus(now, task, status);
		if(effective != Priority.MAX_PRIORITY) {
			List<Task> doAfters = doAftersByTask.get(task);
			if(doAfters != null) {
				for(Task doAfter : doAfters) {
					StatusResult doAfterStatus = getStatus(
						servletContext,
						request,
						response,
						doAfter,
						cache,
						statusCache
					);
					if(
						!doAfterStatus.isCompletedSchedule()
						&& !doAfterStatus.isReadySchedule()
						&& !doAfterStatus.isFutureSchedule()
					) {
						Priority inherited = getEffectivePriority(
							servletContext,
							request,
							response,
							cache,
							statusCache,
							now,
							doAfter,
							doAfterStatus,
							doAftersByTask,
							effectivePriorities
						);
						if(inherited.compareTo(effective) > 0) {
							effective = inherited;
							if(effective == Priority.MAX_PRIORITY) break;
						}
					}
				}
			}
		}
		// Cache result
		effectivePriorities.put(task, effective);
		return effective;
	}

	public static List<Task> prioritizeTasks(
		final ServletContext servletContext,
		final HttpServletRequest request,
		final HttpServletResponse response,
		Collection<? extends Task> tasks,
		final boolean dateFirst
	) throws TaskException, ServletException, IOException {
		final long now = System.currentTimeMillis();
		final Cache cache = CacheFilter.getCache(request);
		final Map<Task, StatusResult> statusCache = getStatusCache(cache);
		// Priority inheritance
		List<Task> allTasks = getAllTasks(
			servletContext,
			request,
			response,
			CapturePage.capturePage(
				servletContext,
				request,
				response,
				SemanticCMS.getInstance(servletContext).getRootBook().getContentRoot(),
				CaptureLevel.META
			),
			null
		);
		// Index tasks by page,id
		Map<ElementRef, Task> tasksByKey = AoCollections.newHashMap(allTasks.size());
		for(Task task : allTasks) {
			if(tasksByKey.put(task.getElementRef(), task) != null) {
				throw new AssertionError("Duplicate task (page, id)");
			}
		}
		// Invert dependency DAG for fast lookups for priority inheritance
		final Map<Task, List<Task>> doAftersByTask = AoCollections.newLinkedHashMap(allTasks.size());
		for(Task task : allTasks) {
			for(ElementRef doBeforeRef : task.getDoBefores()) {
				Task doBefore = tasksByKey.get(doBeforeRef);
				if(doBefore==null) throw new AssertionError("Task not found: " + doBeforeRef);
				if(doBefore.getPage().getGeneratedIds().contains(doBefore.getId())) throw new TaskException("Not allowed to reference task by generated id, set an explicit id on the task: " + doBefore);
				List<Task> doAfters = doAftersByTask.get(doBefore);
				if(doAfters == null) {
					doAfters = new ArrayList<>();
					doAftersByTask.put(doBefore, doAfters);
				}
				doAfters.add(task);
			}
		}
		// Caches the effective priorities for tasks being prioritized or any other resolved in processing
		final Map<Task, Priority> effectivePriorities = new HashMap<>();
		// Build new list and sort
		List<Task> sortedTasks = new ArrayList<>(tasks);
		Collections.sort(
			sortedTasks,
			new Comparator<Task>() {
				private int dateDiff(Task t1, Task t2) throws TaskException, ServletException, IOException {
					// Sort by scheduled or unscheduled
					StatusResult status1 = getStatus(servletContext, request, response, t1, cache, statusCache);
					StatusResult status2 = getStatus(servletContext, request, response, t2, cache, statusCache);
					Calendar date1 = status1.getDate();
					Calendar date2 = status2.getDate();
					int diff = Boolean.compare(date2!=null, date1!=null);
					if(diff!=0) return diff;
					// Then sort by date (if have date in both statuses)
					if(date1!=null && date2!=null) {
						diff = date1.compareTo(date2);
						if(diff!=0) return diff;
					}
					// Dates equal
					return 0;
				}

				@Override
				public int compare(Task t1, Task t2) {
					try {
						// Sort by date (when date first)
						if(dateFirst) {
							int diff = dateDiff(t1, t2);
							if(diff!=0) return diff;
						}
						// Sort by priority (including priority inheritance)
						Priority priority1 = getEffectivePriority(
							servletContext,
							request,
							response,
							cache,
							statusCache,
							now,
							t1,
							getStatus(servletContext, request, response, t1, cache, statusCache),
							doAftersByTask,
							effectivePriorities
						);
						Priority priority2 = getEffectivePriority(
							servletContext,
							request,
							response,
							cache,
							statusCache,
							now,
							t2,
							getStatus(servletContext, request, response, t2, cache, statusCache),
							doAftersByTask,
							effectivePriorities
						);
						int diff = priority2.compareTo(priority1);
						if(diff!=0) return diff;
						// Sort by date (when priority first)
						if(!dateFirst) {
							diff = dateDiff(t1, t2);
							if(diff!=0) return diff;
						}
						// Equal
						return 0;
					} catch(TaskException | ServletException | IOException e) {
						throw new WrappedException(e);
					}
				}
			}
		);
		return Collections.unmodifiableList(sortedTasks);
	}

	private static <V> Map<PageUserKey, V> getPageUserCache(
		final Cache cache,
		String key
	) {
		@SuppressWarnings("unchecked")
		Map<PageUserKey, V> pageUserCache = cache.getAttribute(
			key,
			Map.class,
			() -> cache.newMap()
		);
		return pageUserCache;
	}

	static class PageUserKey extends Tuple2<Page, User> {
		PageUserKey(Page page, User user) {
			super(page, user);
		}
	}

	private static final String ALL_TASKS_CACHE_KEY = TaskUtil.class.getName() + ".getAllTasks";

	public static List<Task> getAllTasks(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Page rootPage,
		final User user
	) throws IOException, ServletException {
		PageUserKey cacheKey = new PageUserKey(rootPage, user);
		Map<PageUserKey, List<Task>> cache = getPageUserCache(CacheFilter.getCache(request), ALL_TASKS_CACHE_KEY);
		List<Task> results = cache.get(cacheKey);
		if(results == null) {
			final List<Task> allTasks = new ArrayList<>();
			final SemanticCMS semanticCMS = SemanticCMS.getInstance(servletContext);
			CapturePage.traversePagesDepthFirst(
				servletContext,
				request,
				response,
				rootPage,
				CaptureLevel.META,
				(Page page, int depth) -> {
					for(Element element : page.getElements()) {
						if(element instanceof Task) {
							Task task = (Task)element;
							if(
								user == null
								|| task.getAssignedTo(user) != null
							) allTasks.add(task);
						}
					}
					return null;
				},
				(Page page) -> page.getChildRefs(),
				// Child in accessible book
				(PageRef childPage) -> semanticCMS.getBook(childPage.getBookRef()).isAccessible(),
				null
			);
			results = Collections.unmodifiableList(allTasks);
			cache.put(cacheKey, results);
		}
		return results;
	}

	private static final String HAS_ASSIGNED_TASK_CACHE_KEY = TaskUtil.class.getName() + ".hasAssignedTask";

	public static boolean hasAssignedTask(
		final ServletContext servletContext,
		final HttpServletRequest request,
		final HttpServletResponse response,
		Page page,
		final User user
	) throws ServletException, IOException {
		PageUserKey cacheKey = new PageUserKey(page, user);
		final Cache cache = CacheFilter.getCache(request);
		final Map<Task, StatusResult> statusCache = getStatusCache(cache);
		Map<PageUserKey, Boolean> hasAssignedTaskCache = getPageUserCache(cache, HAS_ASSIGNED_TASK_CACHE_KEY);
		Boolean result = hasAssignedTaskCache.get(cacheKey);
		if(result == null) {
			final long now = System.currentTimeMillis();
			final SemanticCMS semanticCMS = SemanticCMS.getInstance(servletContext);
			result = CapturePage.traversePagesAnyOrder(
				servletContext,
				request,
				response,
				page,
				CaptureLevel.META,
				(Page p) -> {
					try {
						for(Element element : p.getElements()) {
							if(element instanceof Task) {
								Task task = (Task)element;
								TaskAssignment assignedTo = user == null ? null : task.getAssignedTo(user);
								if(
									user == null
									|| assignedTo != null
								) {
									StatusResult status = getStatus(
										servletContext,
										request,
										response,
										task,
										cache,
										statusCache
									);
									Priority priority = null;
									// getReadyTasks logic
									if(
										!status.isCompletedSchedule()
										&& status.isReadySchedule()
									) {
										priority = TaskHtmlRenderer.getPriorityForStatus(now, task, status);
										if(priority != Priority.FUTURE) {
											if(
												status.getDate() != null
												&& assignedTo != null
												&& assignedTo.getAfter().getCount() > 0
											) {
												// assignedTo "after"
												Calendar effectiveDate = UnmodifiableCalendar.unwrapClone(status.getDate());
												assignedTo.getAfter().offset(effectiveDate);
												if(now >= effectiveDate.getTimeInMillis()) {
													return true;
												}
											} else {
												// No time offset
												return true;
											}
										}
									}
									// getBlockedTasks logic
									if(
										!status.isCompletedSchedule()
										&& !status.isReadySchedule()
										&& !status.isFutureSchedule()
									) {
										if(priority == null) {
											priority = TaskHtmlRenderer.getPriorityForStatus(now, task, status);
										}
										if(priority != Priority.FUTURE) {
											if(
												status.getDate() != null
												&& assignedTo != null
												&& assignedTo.getAfter().getCount() > 0
											) {
												// assignedTo "after"
												Calendar effectiveDate = UnmodifiableCalendar.unwrapClone(status.getDate());
												assignedTo.getAfter().offset(effectiveDate);
												if(now >= effectiveDate.getTimeInMillis()) {
													return true;
												}
											} else {
												// No time offset
												return true;
											}
										}
									}
									// getFutureTasks logic
									if(
										// When assignedTo "after" is non-zero, hide from this user
										assignedTo == null
										|| assignedTo.getAfter().getCount() == 0
									) {
										boolean future = status.isFutureSchedule();
										if(!future) {
											if(priority == null) {
												priority = TaskHtmlRenderer.getPriorityForStatus(now, task, status);
											}
											future = priority == Priority.FUTURE;
										}
										if(future) {
											return true;
										}
									}
								}
							}
						}
						return null;
					} catch(TaskException e) {
						throw new ServletException(e);
					}
				},
				(Page p) -> p.getChildRefs(),
				// Child in accessible book
				(PageRef childPage) -> semanticCMS.getBook(childPage.getBookRef()).isAccessible()
			) != null;
			hasAssignedTaskCache.put(cacheKey, result);
		}
		return result;
	}

	private static final String GET_READY_TASKS_CACHE_KEY = TaskUtil.class.getName() + ".getReadyTasks";

	public static List<Task> getReadyTasks(
		final ServletContext servletContext,
		final HttpServletRequest request,
		final HttpServletResponse response,
		Page rootPage,
		final User user
	) throws IOException, ServletException {
		PageUserKey cacheKey = new PageUserKey(rootPage, user);
		final Cache cache = CacheFilter.getCache(request);
		final Map<Task, StatusResult> statusCache = getStatusCache(cache);
		Map<PageUserKey, List<Task>> getReadyTasksCache = getPageUserCache(cache, GET_READY_TASKS_CACHE_KEY);
		List<Task> results = getReadyTasksCache.get(cacheKey);
		if(results == null) {
			final long now = System.currentTimeMillis();
			final List<Task> readyTasks = new ArrayList<>();
			final SemanticCMS semanticCMS = SemanticCMS.getInstance(servletContext);
			CapturePage.traversePagesDepthFirst(
				servletContext,
				request,
				response,
				rootPage,
				CaptureLevel.META,
				(Page page, int depth) -> {
					try {
						for(Element element : page.getElements()) {
							if(element instanceof Task) {
								Task task = (Task)element;
								TaskAssignment assignedTo = user == null ? null : task.getAssignedTo(user);
								if(
									user == null
									|| assignedTo != null
								) {
									StatusResult status = getStatus(
										servletContext,
										request,
										response,
										task,
										cache,
										statusCache
									);
									if(
										!status.isCompletedSchedule()
										&& status.isReadySchedule()
									) {
										Priority priority = TaskHtmlRenderer.getPriorityForStatus(now, task, status);
										if(priority != Priority.FUTURE) {
											if(
												status.getDate() != null
												&& assignedTo != null
												&& assignedTo.getAfter().getCount() > 0
											) {
												// assignedTo "after"
												Calendar effectiveDate = UnmodifiableCalendar.unwrapClone(status.getDate());
												assignedTo.getAfter().offset(effectiveDate);
												if(now >= effectiveDate.getTimeInMillis()) {
													readyTasks.add(task);
												}
											} else {
												// No time offset
												readyTasks.add(task);
											}
										}
									}
								}
							}
						}
						return null;
					} catch(TaskException e) {
						throw new ServletException(e);
					}
				},
				(Page page) -> page.getChildRefs(),
				// Child in accessible book
				(PageRef childPage) -> semanticCMS.getBook(childPage.getBookRef()).isAccessible(),
				null
			);
			results = Collections.unmodifiableList(readyTasks);
			getReadyTasksCache.put(cacheKey, results);
		}
		return results;
	}

	private static final String GET_BLOCKED_TASKS_CACHE_KEY = TaskUtil.class.getName() + ".getBlockedTasks";

	public static List<Task> getBlockedTasks(
		final ServletContext servletContext,
		final HttpServletRequest request,
		final HttpServletResponse response,
		Page rootPage,
		final User user
	) throws IOException, ServletException {
		PageUserKey cacheKey = new PageUserKey(rootPage, user);
		final Cache cache = CacheFilter.getCache(request);
		final Map<Task, StatusResult> statusCache = getStatusCache(cache);
		Map<PageUserKey, List<Task>> getBlockedTasksCache = getPageUserCache(cache, GET_BLOCKED_TASKS_CACHE_KEY);
		List<Task> results = getBlockedTasksCache.get(cacheKey);
		if(results == null) {
			final long now = System.currentTimeMillis();
			final List<Task> blockedTasks = new ArrayList<>();
			final SemanticCMS semanticCMS = SemanticCMS.getInstance(servletContext);
			CapturePage.traversePagesDepthFirst(
				servletContext,
				request,
				response,
				rootPage,
				CaptureLevel.META,
				(Page page, int depth) -> {
					try {
						for(Element element : page.getElements()) {
							if(element instanceof Task) {
								Task task = (Task)element;
								TaskAssignment assignedTo = user == null ? null : task.getAssignedTo(user);
								if(
									user == null
									|| assignedTo != null
								) {
									StatusResult status = getStatus(
										servletContext,
										request,
										response,
										task,
										cache,
										statusCache
									);
									if(
										!status.isCompletedSchedule()
										&& !status.isReadySchedule()
										&& !status.isFutureSchedule()
									) {
										Priority priority = TaskHtmlRenderer.getPriorityForStatus(now, task, status);
										if(priority != Priority.FUTURE) {
											if(
												status.getDate() != null
												&& assignedTo != null
												&& assignedTo.getAfter().getCount() > 0
											) {
												// assignedTo "after"
												Calendar effectiveDate = UnmodifiableCalendar.unwrapClone(status.getDate());
												assignedTo.getAfter().offset(effectiveDate);
												if(now >= effectiveDate.getTimeInMillis()) {
													blockedTasks.add(task);
												}
											} else {
												// No time offset
												blockedTasks.add(task);
											}
										}
									}
								}
							}
						}
						return null;
					} catch(TaskException e) {
						throw new ServletException(e);
					}
				},
				(Page page) -> page.getChildRefs(),
				// Child in accessible book
				(PageRef childPage) -> semanticCMS.getBook(childPage.getBookRef()).isAccessible(),
				null
			);
			results = Collections.unmodifiableList(blockedTasks);
			getBlockedTasksCache.put(cacheKey, results);
		}
		return results;
	}

	private static final String FUTURE_TASKS_CACHE_KEY = TaskUtil.class.getName() + ".getFutureTasks";

	public static List<Task> getFutureTasks(
		final ServletContext servletContext,
		final HttpServletRequest request,
		final HttpServletResponse response,
		Page rootPage,
		final User user
	) throws IOException, ServletException {
		PageUserKey cacheKey = new PageUserKey(rootPage, user);
		final Cache cache = CacheFilter.getCache(request);
		final Map<Task, StatusResult> statusCache = getStatusCache(cache);
		Map<PageUserKey, List<Task>> futureTasksCache = getPageUserCache(cache, FUTURE_TASKS_CACHE_KEY);
		List<Task> results = futureTasksCache.get(cacheKey);
		if(results == null) {
			final long now = System.currentTimeMillis();
			final List<Task> futureTasks = new ArrayList<>();
			final SemanticCMS semanticCMS = SemanticCMS.getInstance(servletContext);
			CapturePage.traversePagesDepthFirst(
				servletContext,
				request,
				response,
				rootPage,
				CaptureLevel.META,
				(Page page, int depth) -> {
					try {
						for(Element element : page.getElements()) {
							if(element instanceof Task) {
								Task task = (Task)element;
								TaskAssignment assignedTo = user == null ? null : task.getAssignedTo(user);
								if(
									(
										user == null
										|| assignedTo != null
									) && (
										// When assignedTo "after" is non-zero, hide from this user
										assignedTo == null
										|| assignedTo.getAfter().getCount() == 0
									)
								) {
									StatusResult status = getStatus(
										servletContext,
										request,
										response,
										task,
										cache,
										statusCache
									);
									boolean future = status.isFutureSchedule();
									if(!future) {
										Priority priority = TaskHtmlRenderer.getPriorityForStatus(now, task, status);
										future = priority == Priority.FUTURE;
									}
									if(future) {
										futureTasks.add(task);
									}
								}
							}
						}
						return null;
					} catch(TaskException e) {
						throw new ServletException(e);
					}
				},
				(Page page) -> page.getChildRefs(),
				// Child in accessible book
				(PageRef childPage) -> semanticCMS.getBook(childPage.getBookRef()).isAccessible(),
				null
			);
			results = Collections.unmodifiableList(futureTasks);
			futureTasksCache.put(cacheKey, results);
		}
		return results;
	}

	/**
	 * Make no instances.
	 */
	private TaskUtil() {
	}
}