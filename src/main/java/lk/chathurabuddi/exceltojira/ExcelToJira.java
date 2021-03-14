package lk.chathurabuddi.exceltojira;

/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 Chathura Buddhika
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.chathurabuddi.exceltojira.config.AppConfig;
import lk.chathurabuddi.exceltojira.config.ConfigLoader;
import lk.chathurabuddi.exceltojira.config.Excel;
import lk.chathurabuddi.exceltojira.config.Jira;
import lk.chathurabuddi.exceltojira.jira.rq.Assignee;
import lk.chathurabuddi.exceltojira.jira.rq.Fields;
import lk.chathurabuddi.exceltojira.jira.rq.Issue;
import lk.chathurabuddi.exceltojira.jira.rq.Issuetype;
import lk.chathurabuddi.exceltojira.jira.rq.Priority;
import lk.chathurabuddi.exceltojira.jira.rq.Project;
import lk.chathurabuddi.exceltojira.jira.rs.CreateIssueRs;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@Slf4j
public class ExcelToJira {

    private static final AppConfig appConfig = ConfigLoader.get();
    private static final Excel excel = appConfig.getExcel();
    private static final Jira jira = appConfig.getJira();
    private static final Map<String, String> fieldMapping = appConfig.getFieldMapping();
    private static final OkHttpClient client = new OkHttpClient();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {

        Workbook workbook = null;

        try (FileInputStream file = new FileInputStream(excel.getPath())){
            workbook = new XSSFWorkbook(file);
            Sheet sheet = workbook.getSheetAt(0);

            for (int rowId = excel.getSkipRows(); rowId < excel.getLimitRows(); rowId++) {
                Row row = sheet.getRow(rowId);

                Issue issue = Issue.builder().fields(Fields.builder()
                    .summary(getCell(row, "summary").getStringCellValue())
                    .description(getCell(row, "description").getStringCellValue())
                    .labels(getCell(row, "labels").getStringCellValue().split(","))
                    .issuetype(Issuetype.builder().name(getCell(row, "issuetype").getStringCellValue()).build())
                    .project(Project.builder().key(getCell(row, "project").getStringCellValue()).build())
                    .priority(Priority.builder().name(getCell(row, "priority").getStringCellValue()).build())
                    .assignee(Assignee.builder().name(getCell(row, "assignee").getStringCellValue()).build())
                    .build()
                ).build();

                final String jsonBody = objectMapper.writeValueAsString(issue);
                final String credential = Credentials.basic(jira.getUsername(), jira.getPassword());
                Request request = new Request.Builder()
                    .header("Authorization", credential)
                    .url(jira.getUrl() + "/rest/api/latest/issue")
                    .post(RequestBody.create(JSON, jsonBody))
                    .build();

                try (Response response = client.newCall(request).execute()) {
                    final CreateIssueRs createIssueRs = objectMapper.readValue(response.body().string(), CreateIssueRs.class);
                    final String issueKey = createIssueRs.getKey();
                    log.info("issue created successfully [ id:{} ]", issueKey);
                    setCellValueAsLink(
                        workbook,
                        getCell(row, "id"),
                        issueKey,
                        jira.getUrl() + "/browse/" + issueKey
                    );
                }
            }
        } catch (Exception e) {
            log.error("Error occurred while creating JIRA tickets", e);
        }

        if (workbook != null) {
            try(FileOutputStream outputStream = new FileOutputStream(excel.getPath())) {
                workbook.write(outputStream);
                workbook.close();
            } catch (IOException e) {
                log.error("Error occurred while updating EXCEL workbook", e);
            }
        }

    }

    private static void setCellValueAsLink(Workbook workbook, Cell cell, String cellText, String url) {
        cell.setCellValue(cellText);

        final Hyperlink link = workbook.getCreationHelper().createHyperlink(HyperlinkType.URL);
        link.setAddress(url);
        cell.setHyperlink(link);

        CellStyle cellStyle = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setUnderline(Font.U_SINGLE);
        font.setColor(Font.COLOR_RED);
        cellStyle.setFont(font);
        cell.setCellStyle(cellStyle);
    }

    private static Cell getCell(Row row, String key) throws Exception {
        final Cell cell = row.getCell(Integer.parseInt(fieldMapping.get(key)));
        if (cell == null) {
            log.error("invalid cell. empty cells are not allowed [ key:{} ]", key);
            throw new Exception("invalid cell. empty cell for " + key + " is not allowed");
        }
        return cell;
    }
}
