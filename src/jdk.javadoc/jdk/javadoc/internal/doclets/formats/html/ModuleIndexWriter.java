/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package jdk.javadoc.internal.doclets.formats.html;

import java.util.*;

import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;

import jdk.javadoc.internal.doclets.formats.html.markup.ContentBuilder;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlStyle;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlTag;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlTree;
import jdk.javadoc.internal.doclets.formats.html.markup.RawHtml;
import jdk.javadoc.internal.doclets.formats.html.markup.StringContent;
import jdk.javadoc.internal.doclets.toolkit.Content;
import jdk.javadoc.internal.doclets.toolkit.util.DocFileIOException;
import jdk.javadoc.internal.doclets.toolkit.util.DocPath;
import jdk.javadoc.internal.doclets.toolkit.util.DocPaths;
import jdk.javadoc.internal.doclets.toolkit.util.Group;

/**
 * Generate the module index page "overview-summary.html" for the right-hand
 * frame.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 *
 * @author Bhavesh Patel
 */
public class ModuleIndexWriter extends AbstractModuleIndexWriter {

    /**
     * Set representing the modules.
     *
     * @see Group
     */
    private final SortedSet<ModuleElement> modules;

    /**
     * HTML tree for main tag.
     */
    private final HtmlTree htmlTree = HtmlTree.MAIN();

    /**
     * Construct the ModuleIndexWriter.
     * @param configuration the configuration object
     * @param filename the name of the generated file
     */
    public ModuleIndexWriter(ConfigurationImpl configuration, DocPath filename) {
        super(configuration, filename);
        modules = configuration.modules;
    }

    /**
     * Generate the module index page for the right-hand frame.
     *
     * @param configuration the current configuration of the doclet.
     * @throws DocFileIOException if there is a problem generating the module index page
     */
    public static void generate(ConfigurationImpl configuration) throws DocFileIOException {
        DocPath filename = DocPaths.overviewSummary(configuration.frames);
        ModuleIndexWriter mdlgen = new ModuleIndexWriter(configuration, filename);
        mdlgen.buildModuleIndexFile("doclet.Window_Overview_Summary", true);
    }

    /**
     * Add the module index.
     *
     * @param body the documentation tree to which the index will be added
     */
    @Override
    protected void addIndex(Content body) {
        if (modules != null && !modules.isEmpty()) {
            addIndexContents(configuration.getText("doclet.Modules"),
                    configuration.getText("doclet.Member_Table_Summary",
                            configuration.getText("doclet.Module_Summary"),
                            configuration.getText("doclet.modules")), body);
        }
    }

    /**
     * Adds module index contents.
     *
     * @param title the title of the section
     * @param tableSummary summary for the table
     * @param body the document tree to which the index contents will be added
     */
    protected void addIndexContents(String title, String tableSummary, Content body) {
        HtmlTree htmltree = (configuration.allowTag(HtmlTag.NAV))
                ? HtmlTree.NAV()
                : new HtmlTree(HtmlTag.DIV);
        htmltree.addStyle(HtmlStyle.indexNav);
        HtmlTree ul = new HtmlTree(HtmlTag.UL);
        addAllClassesLink(ul);
        if (configuration.showModules) {
            addAllModulesLink(ul);
        }
        htmltree.addContent(ul);
        body.addContent(htmltree);
        addModulesList(title, tableSummary, body);
    }

    /**
     * Add the list of modules.
     *
     * @param text The table caption
     * @param tableSummary the summary of the table tag
     * @param body the content tree to which the module list will be added
     */
    protected void addModulesList(String text, String tableSummary, Content body) {
        Content table = (configuration.isOutputHtml5())
                ? HtmlTree.TABLE(HtmlStyle.overviewSummary, getTableCaption(new RawHtml(text)))
                : HtmlTree.TABLE(HtmlStyle.overviewSummary, tableSummary, getTableCaption(new RawHtml(text)));
        table.addContent(getSummaryTableHeader(moduleTableHeader, "col"));
        Content tbody = new HtmlTree(HtmlTag.TBODY);
        addModulesList(tbody);
        table.addContent(tbody);
        Content div = HtmlTree.DIV(HtmlStyle.contentContainer, table);
        if (configuration.allowTag(HtmlTag.MAIN)) {
            htmlTree.addContent(div);
            body.addContent(htmlTree);
        } else {
            body.addContent(div);
        }
    }

    /**
     * Adds list of modules in the index table. Generate link to each module.
     *
     * @param tbody the documentation tree to which the list will be added
     */
    protected void addModulesList(Content tbody) {
        boolean altColor = true;
        for (ModuleElement mdle : modules) {
            if (!mdle.isUnnamed()) {
                Content moduleLinkContent = getModuleLink(mdle, new StringContent(mdle.getQualifiedName().toString()));
                Content thModule = HtmlTree.TH_ROW_SCOPE(HtmlStyle.colFirst, moduleLinkContent);
                HtmlTree tdSummary = new HtmlTree(HtmlTag.TD);
                tdSummary.addStyle(HtmlStyle.colLast);
                addSummaryComment(mdle, tdSummary);
                HtmlTree tr = HtmlTree.TR(thModule);
                tr.addContent(tdSummary);
                tr.addStyle(altColor ? HtmlStyle.altColor : HtmlStyle.rowColor);
                tbody.addContent(tr);
            }
            altColor = !altColor;
        }
    }

    /**
     * Adds the overview summary comment for this documentation. Add one line
     * summary at the top of the page and generate a link to the description,
     * which is added at the end of this page.
     *
     * @param body the documentation tree to which the overview header will be added
     */
    @Override
    protected void addOverviewHeader(Content body) {
        addConfigurationTitle(body);
        if (!utils.getFullBody(configuration.overviewElement).isEmpty()) {
            HtmlTree div = new HtmlTree(HtmlTag.DIV);
            div.addStyle(HtmlStyle.contentContainer);
            addOverviewComment(div);
            if (configuration.allowTag(HtmlTag.MAIN)) {
                htmlTree.addContent(div);
                body.addContent(htmlTree);
            } else {
                body.addContent(div);
            }
        }
    }

    /**
     * Adds the overview comment as provided in the file specified by the
     * "-overview" option on the command line.
     *
     * @param htmltree the documentation tree to which the overview comment will
     *                 be added
     */
    protected void addOverviewComment(Content htmltree) {
        if (!utils.getFullBody(configuration.overviewElement).isEmpty()) {
            addInlineComment(configuration.overviewElement, htmltree);
        }
    }

    /**
     * Not required for this page.
     *
     * @param body the documentation tree to which the overview will be added
     */
    @Override
    protected void addOverview(Content body) {}

    /**
     * Adds the top text (from the -top option), the upper
     * navigation bar, and then the title (from the"-title"
     * option), at the top of page.
     *
     * @param body the documentation tree to which the navigation bar header will be added
     */
    @Override
    protected void addNavigationBarHeader(Content body) {
        Content htmlTree = (configuration.allowTag(HtmlTag.HEADER))
                ? HtmlTree.HEADER()
                : body;
        addTop(htmlTree);
        addNavLinks(true, htmlTree);
        if (configuration.allowTag(HtmlTag.HEADER)) {
            body.addContent(htmlTree);
        }
    }

    /**
     * Adds the lower navigation bar and the bottom text
     * (from the -bottom option) at the bottom of page.
     *
     * @param body the documentation tree to which the navigation bar footer will be added
     */
    @Override
    protected void addNavigationBarFooter(Content body) {
        Content htmltree = (configuration.allowTag(HtmlTag.FOOTER))
                ? HtmlTree.FOOTER()
                : body;
        addNavLinks(false, htmltree);
        addBottom(htmltree);
        if (configuration.allowTag(HtmlTag.FOOTER)) {
            body.addContent(htmltree);
        }
    }

    @Override
    protected void addModulePackagesList(Map<ModuleElement, Set<PackageElement>> modules, String text,
            String tableSummary, Content body, ModuleElement mdle) {
    }

    @Override
    protected void addModulesList(Map<ModuleElement, Set<PackageElement>> modules, String text,
            String tableSummary, Content body) {
    }
}
