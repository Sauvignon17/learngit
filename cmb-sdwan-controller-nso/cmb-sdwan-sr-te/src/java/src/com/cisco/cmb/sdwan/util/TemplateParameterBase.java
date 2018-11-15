package com.cisco.cmb.sdwan.util;

import org.apache.log4j.Logger;

import com.tailf.conf.ConfException;
import com.tailf.dp.services.ServiceContext;
import com.tailf.navu.NavuNode;
import com.tailf.ncs.template.Template;
import com.tailf.ncs.template.TemplateVariables;

public abstract class TemplateParameterBase {
	private static final Logger LOGGER = Logger.getLogger(TemplateParameterBase.class);
	public TemplateVariables variables = new TemplateVariables();
	public String templateName;

	public TemplateParameterBase(String templateName) {
		this.templateName = templateName;
	}

	public abstract void populate();

	public final void applyTemplate(ServiceContext serviceContext, NavuNode service) throws ConfException {
		populate();
		LOGGER.debug(String.format("Appling template %s using variables %s", templateName, variables));
		Template template = new Template(serviceContext, templateName);
		template.apply(service, variables);
		LOGGER.debug(String.format("Compeleted apply template %s ", templateName));
	}
}
