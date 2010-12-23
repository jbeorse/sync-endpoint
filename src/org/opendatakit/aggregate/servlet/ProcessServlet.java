/*
 * Copyright (C) 2010 University of Washington
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.aggregate.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileUploadException;
import org.opendatakit.aggregate.CallingContext;
import org.opendatakit.aggregate.ContextFactory;
import org.opendatakit.aggregate.constants.BeanDefs;
import org.opendatakit.aggregate.constants.ErrorConsts;
import org.opendatakit.aggregate.datamodel.TopLevelDynamicBase;
import org.opendatakit.aggregate.exception.ODKFormNotFoundException;
import org.opendatakit.aggregate.exception.ODKIncompleteSubmissionData;
import org.opendatakit.aggregate.form.Form;
import org.opendatakit.aggregate.form.MiscTasks;
import org.opendatakit.aggregate.form.MiscTasks.TaskType;
import org.opendatakit.aggregate.parser.MultiPartFormData;
import org.opendatakit.aggregate.process.DeleteSubmissions;
import org.opendatakit.aggregate.process.ProcessParams;
import org.opendatakit.aggregate.process.ProcessType;
import org.opendatakit.aggregate.submission.SubmissionKey;
import org.opendatakit.aggregate.submission.SubmissionKeyPart;
import org.opendatakit.aggregate.task.FormDelete;
import org.opendatakit.common.constants.BasicConsts;
import org.opendatakit.common.persistence.CommonFieldsBase;
import org.opendatakit.common.persistence.exception.ODKDatastoreException;

/**
 * Processes request from web based interface based on users button press
 * specifying the type of processing they want
 * 
 * @author wbrunette@gmail.com
 * @author mitchellsundt@gmail.com
 * 
 */
public class ProcessServlet extends ServletUtilBase {

  /**
   * Serial number for serialization
   */
  private static final long serialVersionUID = 7328196170394698478L;

  /**
   * URI from base
   */
  public static final String ADDR = "admin/process";

  /**
   * Handler for HTTP Post request
   * 
   * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest,
   *      javax.servlet.http.HttpServletResponse)
   */
  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
	CallingContext cc = ContextFactory.getCallingContext(this, ADDR, req);
    StringBuilder errorText = new StringBuilder();

    try {
      ProcessParams params = new ProcessParams(new MultiPartFormData(req));
      List<String> paramKeys = params.getKeys();

      if (paramKeys == null || params.getButtonText() == null) {
        sendErrorNotEnoughParams(resp);
        return;
      }

      List<SubmissionKey> submissionKeys = new ArrayList<SubmissionKey>();
      for (String paramKey : paramKeys) {
        submissionKeys.add(new SubmissionKey(paramKey));
      }

      if (params.getButtonText().equals(ProcessType.DELETE.getButtonText())) {

        String formId = params.getFormId();

        if (formId == null) {
          sendErrorNotEnoughParams(resp);
          return;
        }

        Form form = Form.retrieveForm(formId, cc);

        // don't allow the deletion of the FormInfo submissions.
        if (!form.getFormId().equals(Form.URI_FORM_ID_VALUE_FORM_INFO)) {
          DeleteSubmissions delete = new DeleteSubmissions(submissionKeys, cc);
          delete.deleteSubmissions();
          resp.sendRedirect(cc.getWebApplicationURL(FormsServlet.ADDR));
          return;
        } else {
          String errString = "Attempting to delete FormInfo records!";
          errorText.append(errString);
          Logger.getLogger(this.getClass().getName()).severe(errString);
        }
      } else if (params.getButtonText().equals(ProcessType.DELETE_FORM.getButtonText())) {

        FormDelete formDelete = (FormDelete) cc.getBean(BeanDefs.FORM_DELETE_BEAN);
        for (SubmissionKey submissionKey : submissionKeys) {
          try {
            List<SubmissionKeyPart> parts = submissionKey.splitSubmissionKey();
            if (parts.size() != 2) {
              throw new ODKIncompleteSubmissionData();
            }

            Form form = Form.retrieveForm(parts.get(0).getElementName(), cc);

            if (form == null) {
              throw new ODKFormNotFoundException();
            }

            CommonFieldsBase rel = cc.getDatastore().getEntity(form.getTopLevelGroupElement().getFormDataModel()
                .getBackingObjectPrototype(), parts.get(1).getAuri(), cc.getCurrentUser());
            // If the FormInfo table is the target, log an error!
            if (rel != null) {
              Form formToDelete = new Form((TopLevelDynamicBase) rel, cc);
              if (!formToDelete.getFormId().equals(Form.URI_FORM_ID_VALUE_FORM_INFO)) {
            	MiscTasks m = new MiscTasks(TaskType.DELETE_FORM, formToDelete, null, cc);
            	m.persist(cc);
                formDelete.createFormDeleteTask(formToDelete, m.getSubmissionKey(), 1L, cc);
                resp.sendRedirect(cc.getWebApplicationURL(FormsServlet.ADDR));
                return;
              } else {
                String errString = "Attempting to delete FormInfo table definition record!";
                errorText.append(errString + BasicConsts.NEW_LINE);
                Logger.getLogger(this.getClass().getName()).severe(errString);
              }
            }
          } catch (Exception e) {
            errorText.append(ErrorConsts.TASK_PROBLEM + e.toString() + BasicConsts.NEW_LINE);
            // and try the remaining submissions...
          }
        }
      } else {
        Logger.getLogger(this.getClass().getName()).severe("UNRECOGNIZED PROCESS TYPE!");
        errorBadParam(resp);
        return;
      }
    } catch (ODKFormNotFoundException e) {
      odkIdNotFoundError(resp);
      return;
    } catch (FileUploadException e) {
      e.printStackTrace();
      errorText.append(ErrorConsts.INVALID_PARAMS);
    } catch (ODKDatastoreException e) {
      e.printStackTrace();
      errorText.append(ErrorConsts.PERSISTENCE_LAYER_PROBLEM);
    }
    resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorText.toString());
  }
}