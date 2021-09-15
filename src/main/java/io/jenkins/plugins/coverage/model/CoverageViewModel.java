package io.jenkins.plugins.coverage.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

import j2html.tags.ContainerTag;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.bind.JavaScriptMethod;
import hudson.model.ModelObject;
import hudson.model.Run;

import io.jenkins.plugins.coverage.targets.CoverageElement;
import io.jenkins.plugins.coverage.targets.CoverageResult;
import io.jenkins.plugins.coverage.targets.CoverageResult.CoverageStatistics;
import io.jenkins.plugins.coverage.targets.Ratio;
import io.jenkins.plugins.datatables.DefaultAsyncTableContentProvider;
import io.jenkins.plugins.datatables.TableColumn;
import io.jenkins.plugins.datatables.TableColumn.ColumnCss;
import io.jenkins.plugins.datatables.TableModel;
import io.jenkins.plugins.datatables.TableModel.DetailedColumnDefinition;

import static j2html.TagCreator.*;

/**
 * Server side model that provides the data for the details view of the coverage results. The layout of the associated
 * view is defined corresponding jelly view 'index.jelly'.
 *
 * @author Ullrich Hafner
 */
public class CoverageViewModel extends DefaultAsyncTableContentProvider implements ModelObject {
    private static final CoverageElement LINE_COVERAGE = CoverageElement.LINE;
    private static final CoverageElement BRANCH_COVERAGE = CoverageElement.CONDITIONAL;

    private final Run<?, ?> owner;
    private final CoverageResult result;
    private final String displayName;

    /**
     * Creates a new view model instance.
     *
     * @param owner
     *         the owner of this view
     * @param result
     *         the results to be shown
     * @param displayName
     *         human-readable name of this view (used in bread-crumb)
     */
    public CoverageViewModel(final Run<?, ?> owner, final CoverageResult result, final String displayName) {
        this.owner = owner;
        this.result = result;
        this.displayName = displayName;
    }

    public Run<?, ?> getOwner() {
        return owner;
    }

    public CoverageResult getResult() {
        return result;
    }

    @Override
    public String getDisplayName() {
        return Messages.Coverage_Title(displayName);
    }

    /**
     * Interface for javascript code to get code coverage result.
     *
     * @return aggregated coverage results
     */
    @JavaScriptMethod
    public List<CoverageStatistics> getOverallStatistics() {
        List<CoverageStatistics> results = new ArrayList<>();

        List<Entry<CoverageElement, Ratio>> elements = new ArrayList<>(getResult().getResults().entrySet());
        elements.sort(Collections.reverseOrder(Entry.comparingByKey()));

        for (Map.Entry<CoverageElement, Ratio> c : elements) {
            results.add(new CoverageStatistics(c.getKey().getName(), c.getValue()));
        }

        return results;
    }

    /**
     * Returns the root of the tree of nodes for the ECharts treemap. This tree is used as model for the chart
     * on the client side.
     *
     * @return the tree of nodes for the ECharts treemap
     */
    @JavaScriptMethod
    @SuppressWarnings("unused")
    public TreeChartNode getCoverageTree() {
        CoverageNode tree = getCoverage();
        tree.splitPackages();
        return tree.toChartTree();
    }

    private CoverageNode getCoverage() {
        return CoverageNode.fromResult(getResult());
    }

    @Override
    public TableModel getTableModel(final String id) {
        return new CoverageTableModel(getCoverage());
    }

    /**
     * Returns a new sub-page for the selected link.
     *
     * @param link
     *         the link to identify the sub-page to show
     * @param request
     *         Stapler request
     * @param response
     *         Stapler response
     *
     * @return the new sub page
     */
    @SuppressWarnings("unused") // Called by jelly view
    public Object getDynamic(final String link, final StaplerRequest request, final StaplerResponse response) {
//        if (StringUtils.isNotEmpty(link)) {
//            try {
//                int hashCode = Integer.parseInt(link);
//                Optional<CoverageNode> targetResult = getCoverage().find(CoverageElement.FILE, hashCode);
//            }
//            catch (NumberFormatException exception) {
//                // ignore
//            }
//
//        }
        String[] split = link.split("\\.", 2);
        if (split.length == 2) {
            Optional<CoverageResult> targetResult = getResult().find(split[0], split[1]);
            if (targetResult.isPresent()) {
                CoverageResult coverageResult = targetResult.get();
                if (coverageResult.getElement().equals(CoverageElement.FILE)) {
                    return new SourceViewModel(getOwner(), coverageResult, coverageResult.getDisplayName());
                }
                return new CoverageViewModel(getOwner(), coverageResult, coverageResult.getDisplayName());
            }
        }
        return this; // fallback on broken URLs
    }

    private static class CoverageTableModel extends TableModel {
        private final CoverageNode root;

        CoverageTableModel(final CoverageNode root) {
            this.root = root;
        }

        @Override
        public String getId() {
            return "coverage-details";
        }

        @Override
        public List<TableColumn> getColumns() {
            List<TableColumn> columns = new ArrayList<>();

            columns.add(new TableColumn("Package", "packageName"));
            columns.add(new TableColumn("File", "fileName"));
            columns.add(new TableColumn("Line Coverage", "lineCoverageValue").setHeaderClass(ColumnCss.PERCENTAGE));
            columns.add(new TableColumn("Line Coverage", "lineCoverageChart", "number"));
            columns.add(new TableColumn("Branch Coverage", "branchCoverageValue").setHeaderClass(ColumnCss.PERCENTAGE));
            columns.add(new TableColumn("Branch Coverage", "branchCoverageChart", "number"));

            return columns;
        }

        @Override
        public List<Object> getRows() {
            return root.getAll(CoverageElement.FILE).stream()
                    .map(CoverageRow::new).collect(Collectors.toList());
        }
    }

    @SuppressWarnings("PMD.DataClass") // Used to automatically convert to JSON object
    private static class CoverageRow {
        private final CoverageNode root;

        CoverageRow(final CoverageNode root) {
            this.root = root;
        }

        public String getFileName() {
            String fileName = root.getName();
            return a().withHref("file." + fileName.hashCode()).withText(fileName).render();
        }

        public String getPackageName() {
            return root.getParentName();
        }

        public String getLineCoverageValue() {
            return printCoverage(getLineCoverage());
        }

        private Coverage getLineCoverage() {
            return root.getCoverage(LINE_COVERAGE);
        }

        public DetailedColumnDefinition getLineCoverageChart() {
            return createDetailedColumnFor(LINE_COVERAGE);
        }

        public String getBranchCoverageValue() {
            return printCoverage(getBranchCoverage());
        }

        private String printCoverage(final Coverage branchCoverage) {
            if (branchCoverage.isSet()) {
                return String.valueOf(branchCoverage.getCoveredPercentage());
            }
            return "n/a";
        }

        private Coverage getBranchCoverage() {
            return root.getCoverage(BRANCH_COVERAGE);
        }

        public DetailedColumnDefinition getBranchCoverageChart() {
            return createDetailedColumnFor(BRANCH_COVERAGE);
        }

        private DetailedColumnDefinition createDetailedColumnFor(final CoverageElement element) {
            Coverage coverage = root.getCoverage(element);

            return new DetailedColumnDefinition(getBarChartFor(coverage), String.valueOf(coverage.getCoveredPercentage()));
        }

        private String getBarChartFor(final Coverage coverage) {
            return join(getBarChart("covered", coverage.getCoveredPercentage()),
                    getBarChart("missed", coverage.getMissedPercentage())).render();
        }

        private ContainerTag getBarChart(final String className, final double percentage) {
            return span().withClasses("bar-graph", className, className + "--hover")
                    .withStyle("width:" + (percentage * 100) + "%").withText(".");
        }
    }
}
