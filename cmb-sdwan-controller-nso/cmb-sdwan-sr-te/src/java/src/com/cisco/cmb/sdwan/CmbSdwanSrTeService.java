package com.cisco.cmb.sdwan;

import java.util.Properties;

import org.apache.log4j.Logger;

import com.cisco.cmb.sdwan.exception.DataInvalidationException;
import com.cisco.cmb.sdwan.model.ExplictPath;
import com.cisco.cmb.sdwan.model.Hop;
import com.cisco.cmb.sdwan.model.Lsp;
import com.cisco.cmb.sdwan.model.template.ExplictPathParameter;
import com.cisco.cmb.sdwan.model.template.SbfdParameter;
import com.cisco.cmb.sdwan.model.template.TunnelBasicParamter;
import com.cisco.cmb.sdwan.model.template.TunnelBfdParamter;
import com.cisco.cmb.sdwan.model.template.TunnelPathOptionParamter;
import com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe;
import com.cisco.cmb.sdwan.util.IOSXRHelper;
import com.tailf.conf.ConfException;
import com.tailf.conf.ConfPath;
import com.tailf.dp.DpCallbackException;
import com.tailf.dp.annotations.ServiceCallback;
import com.tailf.dp.proto.ServiceCBType;
import com.tailf.dp.services.ServiceContext;
import com.tailf.dp.services.ServiceOperationType;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuList;
import com.tailf.navu.NavuNode;

public class CmbSdwanSrTeService {
	private static final Logger LOGGER = Logger.getLogger(CmbSdwanSrTeService.class);

	@ServiceCallback(servicePoint = ciscoNsoCmbSdwanSrTe.servicepoint_cisco_nso_cmb_sdwan_servicepoint, callType = ServiceCBType.PRE_MODIFICATION)
	public Properties postModification(ServiceContext context, ServiceOperationType operation, ConfPath path,
			Properties opaque) throws ConfException {
		LOGGER.info("Start CmbSdwanSrTeService preModification().");
		return opaque;
	}

	@ServiceCallback(servicePoint = ciscoNsoCmbSdwanSrTe.servicepoint_cisco_nso_cmb_sdwan_servicepoint, callType = ServiceCBType.CREATE)
	public Properties create(ServiceContext context, NavuNode service, NavuNode root, Properties opaque)
			throws ConfException {
		LOGGER.info("Start CmbSdwanSrTeService create().");
		Lsp lsp = new Lsp(service);

		try {
			configTunnelBasic(context, root, service, lsp);
			configExplictPath(context, root, service, lsp);
			configTunnelPathOption(context, root, service, lsp);
			configSbfd(context, root, service, lsp);
			configTunnelBfd(context, root, service, lsp);
		} catch (DataInvalidationException e) {
			throw new DpCallbackException(e);
		}
		return opaque;
	}

	private void configTunnelBasic(ServiceContext context, NavuNode root, NavuNode service, Lsp lsp)
			throws ConfException {
		TunnelBasicParamter tunnelBasicParamter = new TunnelBasicParamter("tunnel-basic");
		tunnelBasicParamter.setDeviceName(lsp.source());
		tunnelBasicParamter.setTunnelId(lsp.id());
		tunnelBasicParamter.setTunnelLoopbackId(sourceLoopbackId(root, service, lsp));
		tunnelBasicParamter.setTunnelDestination(destinationIp(root, service, lsp));
		if (lsp.forwardClass() != null) {
			tunnelBasicParamter.setForwardClass(lsp.forwardClass());
		}

		tunnelBasicParamter.applyTemplate(context, service);
	}

	private void configExplictPath(ServiceContext context, NavuNode root, NavuNode service, Lsp lsp)
			throws ConfException {
		ExplictPathParameter explictPathParameter = new ExplictPathParameter("explict-path");
		explictPathParameter.setDeviceName(lsp.source());
		for (ExplictPath path : lsp.explicitPathList()) {
			explictPathParameter.setExplicitPathName(path.name());
			for (Hop hop : path.hopList()) {
				explictPathParameter.setExplicitPathIndex(hop.index());
				explictPathParameter.setExplicitPathHopIp(hop.ip());
				explictPathParameter.applyTemplate(context, service);
			}
		}
	}

	private void configTunnelPathOption(ServiceContext context, NavuNode root, NavuNode service, Lsp lsp)
			throws ConfException {
		TunnelPathOptionParamter tunnelPathOptionParamter = new TunnelPathOptionParamter("tunnel-path-option");
		tunnelPathOptionParamter.setDeviceName(lsp.source());
		tunnelPathOptionParamter.setTunnelId(lsp.id());
		for (ExplictPath path : lsp.explicitPathList()) {
			tunnelPathOptionParamter.setTunnelPathOptionIndex(path.pathOptionIndex());
			tunnelPathOptionParamter.setExplicitPathName(path.name());
			tunnelPathOptionParamter.applyTemplate(context, service);
		}
	}

	private void configSbfd(ServiceContext context, NavuNode root, NavuNode service, Lsp lsp) throws ConfException {
		if (!lsp.bfd().exists()) {
			return;
		}
		SbfdParameter sbfdParameter = new SbfdParameter("sbfd");
		sbfdParameter.setDeviceName(lsp.source());
		sbfdParameter.setSbfdRemoteTarget(destinationIp(root, service, lsp));
		sbfdParameter.setSbfdRemoteDiscriminator(sbfdRemoteDiscriminator(root, service, lsp));
		sbfdParameter.applyTemplate(context, service);
	}

	private void configTunnelBfd(ServiceContext context, NavuNode root, NavuNode service, Lsp lsp)
			throws ConfException {
		if (!lsp.bfd().exists()) {
			return;
		}

		TunnelBfdParamter tunnelBfdParamter = new TunnelBfdParamter("tunnel-bfd");
		tunnelBfdParamter.setDeviceName(lsp.source());
		tunnelBfdParamter.setTunnelId(lsp.id());
		tunnelBfdParamter.setTunnelBfdMutiplier(lsp.bfd().multiplier());
		tunnelBfdParamter.setTunnelBfdMinimumInterval(lsp.bfd().minimumInterval());
		tunnelBfdParamter.applyTemplate(context, service);
	}

	private String sourceLoopbackId(NavuNode root, NavuNode service, Lsp lsp) throws NavuException {
		String id = lsp.sourceLoopbackId();
		String message = "";
		if (id == null) {
			IOSXRHelper ios = new IOSXRHelper(root, lsp.source());
			id = ios.minLoopbackId();
		}
		if (id == null) {
			message = String.format("Loopback interface not exists in Source device");
			LOGGER.error(message);
			throw new DataInvalidationException(message);
		}
		return id;
	}

	private String destinationLoopbackId(NavuNode root, NavuNode service, Lsp lsp) throws NavuException {
		String id = lsp.destinationLoopbackId();
		String message = "";
		if (id == null) {
			IOSXRHelper ios = new IOSXRHelper(root, lsp.destination());
			id = ios.minLoopbackId();

		}
		if (id == null) {
			message = String.format("Loopback interface not exists in destination device");
			LOGGER.error(message);
			throw new DataInvalidationException(message);
		}
		return id;
	}

	private String destinationIp(NavuNode root, NavuNode service, Lsp lsp) throws NavuException {
		IOSXRHelper ios = new IOSXRHelper(root, lsp.destination());
		NavuList loopbacks = ios.loopbacks();
		String id = destinationLoopbackId(root, service, lsp);
		String message = "";
		if (loopbacks.elem(id) == null) {
			message = String.format("Loopback id %s invalid in destination device", id);
			LOGGER.error(message);
			throw new DataInvalidationException(message);
		}
		String ipAddr = loopbacks.elem(id).container("ipv4").container("address").leaf("ip").valueAsString();
		if (ipAddr == null) {
			message = String.format("Loopback %s ip address not configured in destination device", id);
			LOGGER.error(message);
			throw new DataInvalidationException(message);
		}

		return ipAddr;
	}

	private String sbfdRemoteDiscriminator(NavuNode root, NavuNode service, Lsp lsp) throws NavuException {
		IOSXRHelper ios = new IOSXRHelper(root, lsp.destination());
		String id = ios.minLocalDiscriminatorId();
		String message = "";
		if (id == null) {
			message = String.format("Remote discriminator id not exists in desination device");
			LOGGER.error(message);
			throw new DataInvalidationException(message);
		}
		return id;
	}

}
