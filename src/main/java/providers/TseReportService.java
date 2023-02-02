package providers;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Stack;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dataset.Dataset;
import dataset.IDataset;
import dataset.RCLDatasetStatus;
import formula.Formula;
import formula.FormulaDecomposer;
import formula.FormulaException;
import formula.FormulaSolver;
import message.MessageConfigBuilder;
import report.Report;
import report_downloader.TSEFormulaDecomposer;
import soap_interface.IGetAck;
import soap_interface.IGetDataset;
import soap_interface.IGetDatasetsList;
import soap_interface.ISendMessage;
import table_relations.Relation;
import table_skeleton.TableCell;
import table_skeleton.TableRow;
import table_skeleton.TableRowList;
import table_skeleton.TableVersion;
import tse_analytical_result.AnalyticalResult;
import tse_case_report.CaseReport;
import tse_config.CustomStrings;
import tse_report.TseReport;
import tse_summarized_information.SummarizedInfo;
import tse_validator.CaseReportValidator;
import tse_validator.ResultValidator;
import xlsx_reader.TableSchema;
import xlsx_reader.TableSchemaList;
import xlsx_reader.TableHeaders.XlsxHeader;

/**
 * 
 * Report service
 * 
 * @author avonva
 * @author shahaal
 *
 */

public class TseReportService extends ReportService {

	private static final Logger LOGGER = LogManager.getLogger(TseReportService.class);

	private IFormulaService formulaService1;

	public TseReportService(IGetAck getAck, IGetDatasetsList<IDataset> getDatasetsList, ISendMessage sendMessage,
			IGetDataset getDataset, ITableDaoService daoService, IFormulaService formulaService) {
		super(getAck, getDatasetsList, sendMessage, getDataset, daoService, formulaService);

		this.formulaService1 = formulaService;
	}

	/**
	 * get sampId field in row
	 * 
	 * @author shahaal
	 * @param summInfo
	 * @return
	 * @throws FormulaException
	 */
	public String getSampId(SummarizedInfo summInfo) throws FormulaException {

		// we need all the fields to compute the context id, in order to
		// solve formula dependencies
		FormulaSolver solver = new FormulaSolver(summInfo, daoService);

		ArrayList<Formula> formulas = solver.solveAll(XlsxHeader.LABEL_FORMULA.getHeaderName());

		LOGGER.info("Formulas: ", Arrays.asList(formulas));

		for (Formula f : formulas) {
			if (f.getColumn().getId().equals(CustomStrings.SAMPLE_ID_COL))
				return f.getSolvedFormula();
		}

		return null;
	}

	/**
	 * Check if the analytical result is related to random genotyping
	 * 
	 * @param row
	 * @return
	 * @throws ParseException
	 */
	public static boolean isRGTResult(TableRow row) throws ParseException {

		FormulaDecomposer decomposer = new FormulaDecomposer();
		String paramBaseTerm = decomposer.getBaseTerm(row.getCode(CustomStrings.PARAM_CODE_COL));

		boolean rgtParamCode = paramBaseTerm.equals(CustomStrings.RGT_PARAM_CODE);

		LOGGER.info("Αnalytical result is related to random genotyping: " + rgtParamCode);
		return rgtParamCode;
	}

	public enum RowType {
		SUMM, CASE, RESULT
	}

	/**
	 * Get the type of the row
	 * 
	 * @param row
	 * @return
	 */
	public static RowType getRowType(TableRow row) {

		RowType type = null;

		switch (row.getSchema().getSheetName()) {
		case CustomStrings.SUMMARIZED_INFO_SHEET:
			type = RowType.SUMM;
			break;
		case CustomStrings.CASE_INFO_SHEET:
			type = RowType.CASE;
			break;
		case CustomStrings.RESULT_SHEET:
			type = RowType.RESULT;
			break;
		default:
			break;
		}
		LOGGER.debug("Row type: ", type);
		return type;
	}

	/**
	 * Extract the origSampId from analytical result
	 * 
	 * @author shahaal
	 * @param result
	 * @return
	 * @throws ParseException
	 * @throws FormulaException
	 */
	public static String getOrigSampIdFrom(TableRow result) throws ParseException, FormulaException {

		// decompose param code
		TSEFormulaDecomposer decomposer = new TSEFormulaDecomposer();

		HashMap<String, TableCell> rowValues = decomposer.decompose(CustomStrings.SAMP_INFO_COL,
				result.getCode(CustomStrings.SAMP_INFO_COL));

		// get the cell for origSampId
		TableCell cell = rowValues.get(CustomStrings.ORIG_SAMP_ID_COL);

		// if the cell is null (old report) then retrieve it from resId
		if (cell == null) {

			// return the substring if dot present
			String[] split = result.getCode(CustomStrings.RES_ID_COL).split("\\.");
			
			// @TODO to better check, what happens if split not possible
			if (split.length >= 1)
				return split[0];
		}
		LOGGER.info("Samp orig id=" + cell.getCode() + " for " + result);
		// return the sampOrigId
		return cell.getCode();
	}

	/**
	 * Get all the elements of the report (summ info, case, analytical results)
	 * 
	 * @return
	 */
	public ArrayList<TableRow> getAllRecords(TseReport report) {

		// children schemas
		TableSchema[] schemas = new TableSchema[] { TableSchemaList.getByName(CustomStrings.SUMMARIZED_INFO_SHEET),
				TableSchemaList.getByName(CustomStrings.CASE_INFO_SHEET),
				TableSchemaList.getByName(CustomStrings.RESULT_SHEET) };

		return getRecords(report, schemas);
	}

	public ArrayList<TableRow> getRecords(TseReport report, TableSchema[] schemas) {

		ArrayList<TableRow> records = new ArrayList<>();

		// for each child schema get the rows related to the report
		for (TableSchema schema : schemas) {

			ArrayList<TableRow> children = getDaoService().getByParentId(schema, CustomStrings.REPORT_SHEET,
					report.getDatabaseId(), true, "desc");

			if (children != null)
				records.addAll(children);
		}

		return records;
	}

	/**
	 * Check if a row has children or not
	 * 
	 * @param parent
	 * @param childSchema
	 * @return
	 */
	public boolean hasChildren(TableRow parent, TableSchema childSchema) {
		return !getDaoService()
				.getByParentId(childSchema, parent.getSchema().getSheetName(), parent.getDatabaseId(), false).isEmpty();
	}

	public void updateChildrenErrors(SummarizedInfo summInfo) {

		// check children errors
		boolean errors = false;
		CaseReportValidator validator = new CaseReportValidator(getDaoService());

		for (TableRow row : getDaoService().getByParentId(TableSchemaList.getByName(CustomStrings.CASE_INFO_SHEET),
				summInfo.getSchema().getSheetName(), summInfo.getDatabaseId(), true)) {

			if (validator.getOverallWarningLevel(row) > 0) {
				summInfo.setChildrenError();
				errors = true;
				break;
			}
		}

		if (!errors) {
			summInfo.removeChildrenError();
		}

		getDaoService().update(summInfo);
	}

	public void updateChildrenErrors(CaseReport caseReport) {

		// check children errors
		boolean error = false;
		ResultValidator resultValidator = new ResultValidator();
		for (TableRow r : getDaoService().getByParentId(TableSchemaList.getByName(CustomStrings.RESULT_SHEET),
				caseReport.getSchema().getSheetName(), caseReport.getDatabaseId(), true)) {

			if (resultValidator.getWarningLevel(r) > 0) {
				caseReport.setChildrenError();
				error = true;
				break;
			}
		}

		if (!error) {
			caseReport.removeChildrenError();
		}

		getDaoService().update(caseReport);
	}

	public MessageConfigBuilder getSendMessageConfiguration(TseReport report) {

		Collection<TableRow> messageParents = new ArrayList<>();

		// add the report data
		messageParents.add(report);

		// add the settings data
		try {

			TableRow settings = Relation.getGlobalParent(CustomStrings.SETTINGS_SHEET, getDaoService());

			messageParents.add(settings);
		} catch (IOException e) {
			LOGGER.error("Error in setting message data", e);
			e.printStackTrace();
		}

		MessageConfigBuilder builder = new MessageConfigBuilder(formulaService1, messageParents);

		return builder;
	}

	public MessageConfigBuilder getAcceptDwhBetaMessageConfiguration(TseReport report) {

		Collection<TableRow> messageParents = new ArrayList<>();

		// add the report data
		messageParents.add(report);

		// add the settings data
		try {

			TableRow settings = Relation.getGlobalParent(CustomStrings.SETTINGS_SHEET, getDaoService());

			messageParents.add(settings);
		} catch (IOException e) {
			LOGGER.error("Error in setting Accept Dwh Beta message data", e);
			e.printStackTrace();
		}

		MessageConfigBuilder builder = new MessageConfigBuilder(formulaService1, messageParents);

		return builder;
	}

	/**
	 * Create a new version of the report and save it into the database. The version
	 * is automatically increased
	 * 
	 * @return
	 */
	public TseReport amend(TseReport report) {

		TseReport amendedReport = new TseReport();
		amendedReport.copyValues(report);

		Stack<TableRow> elements = new Stack<>();
		elements.add(amendedReport);

		SummarizedInfo summInfo = null;
		CaseReport caseReport = null;
		AnalyticalResult result = null;
		while (!elements.isEmpty()) {

			TableRow currentElement = elements.pop();

			boolean isReport = currentElement.getSchema().equals(TableSchemaList.getByName(CustomStrings.REPORT_SHEET));
			boolean isSumm = currentElement.getSchema()
					.equals(TableSchemaList.getByName(CustomStrings.SUMMARIZED_INFO_SHEET));
			boolean isCase = currentElement.getSchema()
					.equals(TableSchemaList.getByName(CustomStrings.CASE_INFO_SHEET));
			boolean isResult = currentElement.getSchema().equals(TableSchemaList.getByName(CustomStrings.RESULT_SHEET));

			TableSchema childSchema = null;
			if (isReport) {
				childSchema = TableSchemaList.getByName(CustomStrings.SUMMARIZED_INFO_SHEET);
			} else if (isSumm) {
				childSchema = TableSchemaList.getByName(CustomStrings.CASE_INFO_SHEET);
			} else if (isCase) {
				childSchema = TableSchemaList.getByName(CustomStrings.RESULT_SHEET);
			}

			// get the element children (before changing its id)
			Collection<TableRow> children = null;

			if (!isResult) {
				children = getDaoService().getByParentId(childSchema, currentElement.getSchema().getSheetName(),
						currentElement.getDatabaseId(), true);
			}

			if (isReport) {
				// get current version
				String currentVersion = report.getVersion();

				// increase version starting from the current
				String newVersion = TableVersion.createNewVersion(currentVersion);

				amendedReport.setVersion(newVersion);

				// new version is in draft
				amendedReport.setStatus(RCLDatasetStatus.DRAFT);

				amendedReport.setId("");
				amendedReport.setMessageId("");
				getDaoService().add(amendedReport);
			} else if (isSumm) {
				summInfo = new SummarizedInfo();
				summInfo.copyValues(currentElement);
				Relation.injectParent(amendedReport, summInfo);
				getDaoService().add(summInfo);
			} else if (isCase) {
				caseReport = new CaseReport();
				caseReport.copyValues(currentElement);
				Relation.injectParent(amendedReport, caseReport);
				Relation.injectParent(summInfo, caseReport);
				getDaoService().add(caseReport);
			} else if (isResult) {
				result = new AnalyticalResult();
				result.copyValues(currentElement);
				Relation.injectParent(amendedReport, result);
				Relation.injectParent(summInfo, result);
				Relation.injectParent(caseReport, result);
				getDaoService().add(result);
			}

			// add the children
			if (!isResult)
				elements.addAll(children);
		}
		LOGGER.info("Amended report : ", amendedReport);
		return amendedReport;
	}

	/**
	 * Create a report from a dataset
	 * 
	 * @param dataset
	 * @return
	 */
	public TseReport reportFromDataset(Dataset dataset) {

		TseReport report = new TseReport();

		String senderDatasetId = dataset.getOperation().getSenderDatasetId();

		report.setId(dataset.getId());

		String[] split = Dataset.splitSenderId(senderDatasetId);

		String senderId = senderDatasetId;
		String version = null;

		if (split != null && split.length > 1) {
			senderId = split[0];
			version = split[1];
			report.setVersion(version);
		} else {
			report.setVersion(TableVersion.getFirstVersion());
		}

		report.setSenderId(senderId);

		if (dataset.getRCLStatus() != null)
			report.setStatus(dataset.getRCLStatus());
		else
			report.setStatus(RCLDatasetStatus.DRAFT);

		// split FR1705... into country year and month
		if (senderId.length() < 6) {
			LOGGER.error("Report#fromDataset Cannot parse sender dataset id, expected at least 6 characters, found "
					+ senderId);
			report.setCountry("");
			report.setYear("");
			report.setMonth("");
		} else {

			String countryCode = senderDatasetId.substring(0, 2);
			String year = "20" + senderDatasetId.substring(2, 4);
			String month = senderDatasetId.substring(4, 6);

			// remove the padding
			if (month.substring(0, 1).equals("0"))
				month = month.substring(1, 2);

			report.setCountry(countryCode);
			report.setYear(year);
			report.setMonth(month);
		}

		// copy message ids
		report.setMessageId(dataset.getLastMessageId());
		report.setLastMessageId(dataset.getLastMessageId());
		report.setLastModifyingMessageId(dataset.getLastModifyingMessageId());
		report.setLastValidationMessageId(dataset.getLastValidationMessageId());

		// add the preferences
		try {
			Relation.injectGlobalParent(report, CustomStrings.PREFERENCES_SHEET, getDaoService());
		} catch (IOException e) {
			LOGGER.error("Error in injecting the parent foreign key into the child row" , e);
			e.printStackTrace();
		}

		// shahaal: removed stmt since the report is not based anymore on the
		// exceptional country
		// but instead on the CWD EXTENDED CONTEXT
		/*
		 * try {
		 * 
		 * //uncomment for printing the columns of the schema in the report //for
		 * (TableColumn tc : report.getSchema()) // System.out.println("Column: " +
		 * tc.getId());
		 * 
		 * String isCWDExtendedContext = formulaService.solve(report,
		 * report.getSchema()..getById(CustomStrings.EXCEPTION_COUNTRY_COL),
		 * XlsxHeader.LABEL_FORMULA);
		 * 
		 * report.setCWDExtendedContext(isCWDExtendedContext);
		 * 
		 * } catch (FormulaException e) { e.printStackTrace(); }
		 */
		LOGGER.info("The report creation by this dataset :" + dataset + "is completed!", report);
		return report;

	}

	public void createDefaultRGTCase(Report report, TableRow summInfo) {

		TableSchema caseSchema = TableSchemaList.getByName(CustomStrings.CASE_INFO_SHEET);
		TableRow resultRow = new TableRow(caseSchema);

		// inject the case parent to the result
		Relation.injectParent(report, resultRow);
		Relation.injectParent(summInfo, resultRow);

		formulaService1.Initialise(resultRow);

		// add get the id and update the fields
		daoService.add(resultRow);

		formulaService1.Initialise(resultRow);

		resultRow.put(CustomStrings.PART_COL, CustomStrings.BLOOD_CODE);

		daoService.update(resultRow);
	}

	/**
	 * Once a summ info is clicked, create the default cases according to number of
	 * positive/inconclusive cases
	 * 
	 * @param summInfo
	 * @param positive
	 * @param inconclusive
	 * @throws IOException
	 */
	public void createDefaultCases(Report report, TableRow summInfo) throws IOException {

		// check cases number
		int positive = summInfo.getNumLabel(CustomStrings.TOT_SAMPLE_POSITIVE_COL);
		int inconclusive = summInfo.getNumLabel(CustomStrings.TOT_SAMPLE_INCONCLUSIVE_COL);

		TableSchema resultSchema = TableSchemaList.getByName(CustomStrings.CASE_INFO_SHEET);

		boolean isCervid = summInfo.getCode(CustomStrings.SUMMARIZED_INFO_TYPE)
				.equals(CustomStrings.SUMMARIZED_INFO_CWD_TYPE);

		// for cervids we need double rows
		int repeats = isCervid ? 2 : 1;

		// for each inconclusive
		for (int i = 0; i < inconclusive; ++i) {

			for (int j = 0; j < repeats; ++j) {

				TableRow resultRow = new TableRow(resultSchema);

				// inject the case parent to the result
				Relation.injectParent(report, resultRow);
				Relation.injectParent(summInfo, resultRow);
				formulaService1.Initialise(resultRow);

				// add result
				daoService.add(resultRow);

				formulaService1.Initialise(resultRow);

				// set assessment as inconclusive
				TableCell value = new TableCell();
				value.setCode(CustomStrings.DEFAULT_ASSESS_INC_CASE_CODE);
				value.setLabel(CustomStrings.DEFAULT_ASSESS_INC_CASE_LABEL);
				resultRow.put(CustomStrings.SAMP_EVENT_ASSES_COL, value);

				// default always obex
				resultRow.put(CustomStrings.PART_COL, CustomStrings.OBEX_CODE);

				if (isCervid) {
					if (j == 0) {
						resultRow.put(CustomStrings.PART_COL, CustomStrings.OBEX_CODE);
					} else if (j == 1) {
						resultRow.put(CustomStrings.PART_COL, CustomStrings.RETROPHARYNGEAL_CODE);
					}
				}

				daoService.update(resultRow);
			}
		}

		// for each positive
		for (int i = 0; i < positive; ++i) {

			for (int j = 0; j < repeats; ++j) {

				TableRow resultRow = new TableRow(resultSchema);

				// inject the case parent to the result
				Relation.injectParent(report, resultRow);
				Relation.injectParent(summInfo, resultRow);
				formulaService1.Initialise(resultRow);

				// add get the id and update the fields
				daoService.add(resultRow);

				// default always obex
				resultRow.put(CustomStrings.PART_COL, CustomStrings.OBEX_CODE);

				if (isCervid) {
					if (j == 0) {
						resultRow.put(CustomStrings.PART_COL, CustomStrings.OBEX_CODE);
					} else if (j == 1) {
						resultRow.put(CustomStrings.PART_COL, CustomStrings.RETROPHARYNGEAL_CODE);
					}
				}

				daoService.update(resultRow);
			}
		}
	}

	public TableRowList createDefaultResults(Report report, SummarizedInfo summInfo, CaseReport caseInfo)
			throws IOException {

		PredefinedResultService r = new PredefinedResultService(daoService, formulaService1);

		TableRowList results = r.createDefaultResults(report, summInfo, caseInfo);

		return results;
	}

}
