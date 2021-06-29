/*
 * pragmatickm-task-renderer-html - Tasks rendered as HTML in a Servlet environment.
 * Copyright (C) 2021  AO Industries, Inc.
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
module com.pragmatickm.task.renderer.html {
	exports com.pragmatickm.task.renderer.html;
	// Direct
	requires com.aoapps.collections; // <groupId>com.aoapps</groupId><artifactId>ao-collections</artifactId>
	requires com.aoapps.concurrent; // <groupId>com.aoapps</groupId><artifactId>ao-concurrent</artifactId>
	requires com.aoapps.encoding; // <groupId>com.aoapps</groupId><artifactId>ao-encoding</artifactId>
	requires com.aoapps.html.any; // <groupId>com.aoapps</groupId><artifactId>ao-fluent-html-any</artifactId>
	requires com.aoapps.hodgepodge; // <groupId>com.aoapps</groupId><artifactId>ao-hodgepodge</artifactId>
	requires com.aoapps.io.buffer; // <groupId>com.aoapps</groupId><artifactId>ao-io-buffer</artifactId>
	requires com.aoapps.lang; // <groupId>com.aoapps</groupId><artifactId>ao-lang</artifactId>
	requires com.aoapps.net.types; // <groupId>com.aoapps</groupId><artifactId>ao-net-types</artifactId>
	requires com.aoapps.servlet.subrequest; // <groupId>com.aoapps</groupId><artifactId>ao-servlet-subrequest</artifactId>
	requires com.aoapps.servlet.util; // <groupId>com.aoapps</groupId><artifactId>ao-servlet-util</artifactId>
	requires com.aoapps.taglib; // <groupId>com.aoapps</groupId><artifactId>ao-taglib</artifactId>
	requires com.aoapps.tempfiles; // <groupId>com.aoapps</groupId><artifactId>ao-tempfiles</artifactId>
	requires com.aoapps.tempfiles.servlet; // <groupId>com.aoapps</groupId><artifactId>ao-tempfiles-servlet</artifactId>
	requires javax.el.api; // <groupId>javax.el</groupId><artifactId>javax.el-api</artifactId>
	requires javax.servlet.api; // <groupId>javax.servlet</groupId><artifactId>javax.servlet-api</artifactId>
	requires com.pragmatickm.task.model; // <groupId>com.pragmatickm</groupId><artifactId>pragmatickm-task-model</artifactId>
	requires com.semanticcms.core.controller; // <groupId>com.semanticcms</groupId><artifactId>semanticcms-core-controller</artifactId>
	requires com.semanticcms.core.model; // <groupId>com.semanticcms</groupId><artifactId>semanticcms-core-model</artifactId>
	requires com.semanticcms.core.pages; // <groupId>com.semanticcms</groupId><artifactId>semanticcms-core-pages</artifactId>
	requires com.semanticcms.core.pages.local; // <groupId>com.semanticcms</groupId><artifactId>semanticcms-core-pages-local</artifactId>
	requires com.semanticcms.core.renderer.html; // <groupId>com.semanticcms</groupId><artifactId>semanticcms-core-renderer-html</artifactId>
	requires com.semanticcms.core.resources; // <groupId>com.semanticcms</groupId><artifactId>semanticcms-core-resources</artifactId>
	// Transitive
	requires com.semanticcms.core.renderer; // <groupId>com.semanticcms</groupId><artifactId>semanticcms-core-renderer</artifactId>
}
