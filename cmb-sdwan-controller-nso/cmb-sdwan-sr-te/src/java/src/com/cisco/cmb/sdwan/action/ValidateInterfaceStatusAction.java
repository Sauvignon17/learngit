package com.cisco.cmb.sdwan.action;

import static com.cisco.cmb.sdwan.model.rpc.ValidateInterfaceStatus.output.enums.InterfaceState.NG;
import static com.cisco.cmb.sdwan.model.rpc.ValidateInterfaceStatus.output.enums.InterfaceState.OK;
import static com.cisco.cmb.sdwan.model.rpc.ValidateInterfaceStatus.output.enums.InterfaceState.UNKNOWN;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe._device_;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe._info_;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe._interface_;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe._ip_address_;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe._name_;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe._protocol_;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe._raw_;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe._result_;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe._state_;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe._status_;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe.actionpoint_validate_interface_status;
import static com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe.prefix;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import com.cisco.cmb.sdwan.model.rpc.ValidateInterfaceStatus.input.InputDevice;
import com.cisco.cmb.sdwan.model.rpc.ValidateInterfaceStatus.output.Interfaze;
import com.cisco.cmb.sdwan.model.rpc.ValidateInterfaceStatus.output.OutputDevice;
import com.cisco.cmb.sdwan.util.CliDeviceFactory;
import com.tailf.conf.ConfBool;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfException;
import com.tailf.conf.ConfList;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfTag;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamStart;
import com.tailf.conf.ConfXMLParamStop;
import com.tailf.conf.ConfXMLParamValue;
import com.tailf.dp.DpActionTrans;
import com.tailf.dp.annotations.ActionCallback;
import com.tailf.dp.proto.ActionCBType;

public class ValidateInterfaceStatusAction {
	private static final Logger LOGGER = Logger.getLogger(ValidateInterfaceStatusAction.class);
	private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(5, Integer.MAX_VALUE, 60L,
			TimeUnit.SECONDS, new SynchronousQueue<Runnable>());

	@ActionCallback(callPoint = actionpoint_validate_interface_status, callType = ActionCBType.ACTION)
	public ConfXMLParam[] action(DpActionTrans trans, ConfTag name, ConfObject[] kp, ConfXMLParam[] params)
			throws ConfException {

		List<InputDevice> inputDeviceList = parseInput(params);
		List<OutputDevice> outputDeviceList = runAllValidation(inputDeviceList);
		List<ConfXMLParam> outputParams = formatOutput(inputDeviceList, outputDeviceList);

		return outputParams.toArray(new ConfXMLParam[outputParams.size()]);
	}

	private List<InputDevice> parseInput(ConfXMLParam[] params) {
		int i = 0;
		List<InputDevice> inputDeviceList = new ArrayList<InputDevice>();
		while (i < params.length - 1) {
			if (params[i] instanceof ConfXMLParamStart && _device_.equals(params[i].getTag())) {
				InputDevice device = new InputDevice();
				while (!(params[++i] instanceof ConfXMLParamStop && _device_.equals(params[i].getTag()))) {
					if (_name_.equals(params[i].getTag())) {
						device.setName(params[i].getValue().toString());
						continue;
					}

					if (_interface_.equals(params[i].getTag())) {
						ConfList iii = (ConfList) params[i].getValue();
						List<String> interfaces = device.getInterfaces();
						for (ConfObject value : iii.elements()) {
							interfaces.add(value.toString());
						}
						continue;
					}
				}
				inputDeviceList.add(device);
				i++;
			}
		}
		return inputDeviceList;
	}

	private List<OutputDevice> runAllValidation(List<InputDevice> inputDeviceList) {
		List<CompletableFuture<Void>> allTasks = new ArrayList<CompletableFuture<Void>>();
		List<OutputDevice> outputDeviceList = new ArrayList<OutputDevice>();
		for (InputDevice inputDevice : inputDeviceList) {
			OutputDevice outputDevice = new OutputDevice(inputDevice.getName());
			CompletableFuture<Void> task = CompletableFuture
					.supplyAsync(() -> CliDeviceFactory.getCliDevice(inputDevice.getName()), EXECUTOR)
					.thenApply(cliDevice -> cliDevice.getAllInterfacesStatus())
					.thenApply(interfacesStatus -> interfacesStatus.stream()
							.map(interfaceStatus -> new Interfaze(interfaceStatus)).collect(Collectors.toList()))
					.thenAccept(interfaces -> outputDevice.setInterfaces(interfaces)).exceptionally(e -> {
						outputDevice.setInfo(e.getMessage());
						return null;
					});
			outputDeviceList.add(outputDevice);
			allTasks.add(task);
		}
		CompletableFuture<Void> all = CompletableFuture.allOf(allTasks.toArray(new CompletableFuture[allTasks.size()]));
		all.join();
		return outputDeviceList;
	}

	private List<ConfXMLParam> formatOutput(List<InputDevice> inputDeviceList, List<OutputDevice> outputDeviceList)
			throws ConfException {

		Map<String, OutputDevice> outputDeviceMap = outputDeviceList.stream()
				.collect(Collectors.toMap(OutputDevice::getName, d -> d));
		List<ConfXMLParam> outputParams = new ArrayList<ConfXMLParam>();
		boolean result = true;

		for (InputDevice inputDevice : inputDeviceList) {
			OutputDevice outputDevice = outputDeviceMap.get(inputDevice.getName());
			outputParams.add(new ConfXMLParamStart(prefix, _device_));
			outputParams.add(new ConfXMLParamValue(prefix, _name_, new ConfBuf(outputDevice.getName())));
			if (outputDevice.getInfo() != null) {
				outputParams.add(new ConfXMLParamValue(prefix, _info_, new ConfBuf(outputDevice.getInfo())));
				outputParams.add(new ConfXMLParamStop(prefix, _device_));
				result = false;
				continue;
			}

			Map<String, Interfaze> outputDeviceInterfaceMap = outputDevice.getInterfaces().stream()
					.collect(Collectors.toMap(Interfaze::getName, d -> d));

			for (String inputInterface : inputDevice.getInterfaces()) {
				outputParams.add(new ConfXMLParamStart(prefix, _interface_));
				outputParams.add(new ConfXMLParamValue(prefix, _name_, new ConfBuf(inputInterface)));
				if (outputDeviceInterfaceMap.keySet().contains(inputInterface)) {
					Interfaze outputInterface = outputDeviceInterfaceMap.get(inputInterface);
					outputParams.add(new ConfXMLParamValue(prefix, _state_,
							outputInterface.isOk() ? OK.confEnumeration() : NG.confEnumeration()));
					outputParams.add(new ConfXMLParamStart(prefix, _raw_));

					outputParams.add(
							new ConfXMLParamValue(prefix, _ip_address_, new ConfBuf(outputInterface.getIpAddress())));
					outputParams.add(new ConfXMLParamValue(prefix, _status_, new ConfBuf(outputInterface.getStatus())));
					outputParams
							.add(new ConfXMLParamValue(prefix, _protocol_, new ConfBuf(outputInterface.getProtocol())));

					outputParams.add(new ConfXMLParamStop(prefix, _raw_));

					if (!outputInterface.isOk()) {
						result = false;
					}
				} else {
					outputParams.add(new ConfXMLParamValue(prefix, _state_, UNKNOWN.confEnumeration()));
					result = false;
				}

				outputParams.add(new ConfXMLParamStop(prefix, _interface_));
			}
			outputParams.add(new ConfXMLParamStop(prefix, _device_));
		}
		outputParams.add(new ConfXMLParamValue(prefix, _result_, new ConfBool(result)));
		return outputParams;
	}

}
