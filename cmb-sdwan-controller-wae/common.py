import json
import random
import string

import ncs
from com.cisco.wae.design import tools as wae_tools
from com.cisco.wae.design.model.net import SegmentListHopRecord
from com.cisco.wae.design.model.net import SegmentListHopType
from contextlib import contextmanager


class LspInfo(object):

    def __init__(self, net, lsp):
        self._populate(net, lsp)

    def _populate(self, net, lsp):
        self.name = str(lsp)
        self.segment_lists = get_lsp_segment_lists(net, lsp)


class InterfaceInfo(object):

    def __init__(self, interface):
        self._populate(interface)

    def _populate(self, interface):
        self.name = str(interface)
        self.sim_util = interface.simulated_utilization
        self.meas_util = interface.measured_utilization


class LspPath(object):
    def __init__(self, net, source, destination, metric="te", avoid_nodes=None, avoid_intfs=None, logger=None):
        self.net = net
        self.source = source
        self.destination = destination
        self.metric = metric
        self.avoid_nodes = avoid_nodes
        self.avoid_intfs = avoid_intfs
        self.logger = logger
        self._populate()
        self._optimize()

    def _populate(self):
        self.lsp = new_lsp(self.net, self.source, self.destination)

    def _optimize(self):
        optimize_sr_lsp(self.net, self.lsp, self.metric, self.avoid_nodes, self.avoid_intfs, self.logger)

    @property
    def segment_list_hops(self):
        if self.lsp.ordered_lsp_paths:
            lsp_path = self.lsp.ordered_lsp_paths[0]
            if lsp_path.segment_list and lsp_path.segment_list.hops:
                return lsp_path.segment_list.hops
        return None

    @property
    def segment_list_hops_ip(self):
        if self.segment_list_hops:
            return get_hops_ips(self.segment_list_hops)
        return None

    @property
    def interfaces(self):
        if self.lsp.ordered_lsp_paths:
            lsp_path = self.lsp.ordered_lsp_paths[0]
            if lsp_path.segment_list and lsp_path.segment_list.hops:
                return self.net.model.route_simulation.routes[lsp_path].interfaces
        return None

    @property
    def segment_list_rpc_hops(self):
        if self.lsp.ordered_lsp_paths:
            lsp_path = self.lsp.ordered_lsp_paths[0]
            if lsp_path.segment_list and lsp_path.segment_list.hops:
                return lsp_path.segment_list.rpc_object.getHops()
        return None

    def reset_segment_list_hops(self, new_segment_list_rpc_hops):
        if self.lsp.ordered_lsp_paths:
            lsp_rpc_object = self.lsp.ordered_lsp_paths[0].segment_list.rpc_object
            lsp_rpc_object.removeAllHops()
            lsp_rpc_object.addHops(new_segment_list_rpc_hops)
            return True
        return False


def new_lsp(net, source, destination, logger=None):
    lsp_dict = {
        'source': source.name,
        'destination': destination.name,
        'name': random_string(10),
        'lsp_type': "segment_routing"
    }
    while lsp_dict in net.model.lsps:
        lsp_dict['name'] = random_string(10)
    if logger:
        logger.debug("Create new LSP from {} to {} with below parameters:".format(source.name, destination.name))
        logger.debug(json.dumps(lsp_dict, indent=4))

    return net.model.lsps.append(lsp_dict)


def new_service_class(net, name):
    service_class_dict = {
        'name': name
    }
    return net.model.service_classes.append(service_class_dict)


def new_demand(net, source, destination):
    demand_dict = {
        'source': str(source),
        'destination': str(destination),
        'service_class': 'Default',
        'name': random_string(10)}
    while demand_dict in net.model.demands:
        demand_dict['name'] = random_string(10)
    return net.model.demands.append(demand_dict)


def optimize_sr_lsp(net, lsp, metric, avoid_nodes=None, avoid_intfs=None, logger=None):
    sr_opts = wae_tools.SRTEOptimizerOptions()
    tool_mgr = net.service_connection.rpc_service_connection.getToolManager()
    sr_te_opt = tool_mgr.newSRTEOptimizer()

    sr_opts.optLSPs = [lsp.rpc_key]
    if metric == "te":
        sr_opts.metric = \
            wae_tools.SRTEOptimizerMetricType.SR_TE_OPT_METRIC_TE
    elif metric == "igp":
        sr_opts.metric = \
            wae_tools.SRTEOptimizerMetricType.SR_TE_OPT_METRIC_IGP
    elif metric == "delay":
        sr_opts.metric = \
            wae_tools.SRTEOptimizerMetricType.SR_TE_OPT_METRIC_DELAY
    if avoid_nodes:
        sr_opts.nodesToAvoid = [node.rpc_key for node in avoid_nodes]
    if avoid_intfs:
        sr_opts.interfacesToAvoid = [intf.rpc_key for intf in avoid_intfs]
    if logger:
        logger.debug("Run SRTEOptimizer with below parameters:")
        logger.debug(sr_opts)

    sr_te_opt.run(net.rpc_plan_network, sr_opts)
    tool_mgr.removeTool(sr_te_opt)

    net.model.cache_valid = False


def create_demand_mesh(net, logger=None):
    service = net.service_connection.rpc_service_connection
    tool_mgr = service.getToolManager()
    demand_mesh_creator = tool_mgr.newDemandMeshCreator()
    demand_mesh_opts = wae_tools.DemandMeshCreatorOptions()

    if logger:
        logger.debug("Run DemandMeshCreator with below parameters:")
        logger.debug(demand_mesh_opts)

    demand_mesh_creator.run(net.rpc_plan_network, demand_mesh_opts)
    tool_mgr.removeTool(demand_mesh_creator)

    net.model.cache_valid = False


def demand_deduction(net, fix_demands, logger=None):
    service = net.service_connection.rpc_service_connection
    tool_mgr = service.getToolManager()
    demand_deduction = tool_mgr.newDemandDeduction()
    dd_opts = wae_tools.DemandDeductionOptions()

    dd_opts.measuredErrors = wae_tools.MeasuredErrorType.MEASUREDERRORTYPE_SPREAD
    dd_opts.useMeasurements = [wae_tools.MeasuredElementType.MEASUREDELEMENTTYPE_IFACE,
                               wae_tools.MeasuredElementType.MEASUREDELEMENTTYPE_LSP]
    dd_opts.ifacePriority = 1
    dd_opts.lspPriority = 1
    dd_opts.fixDemands = [demand.rpc_key for demand in fix_demands]
    dd_opts.removeZeroBWDemands = True

    if logger:
        logger.debug("Run DemandDeduction with below parameters:")
        logger.debug(dd_opts)

    demand_deduction.run(net.rpc_plan_network, dd_opts)
    tool_mgr.removeTool(demand_deduction)

    net.model.cache_valid = False


def optimize_sr_bw(net, util_threshold, opt_intfs=None, fix_lsps=None, fix_demands=None, logger=None):
    service = net.service_connection.rpc_service_connection
    tool_mgr = service.getToolManager()
    sr_bw_opt = tool_mgr.newSRTEBWOptimizer()
    sr_bw_opts = wae_tools.SRTEBWOptimizerOptions()

    sr_bw_opts.utilThreshold = util_threshold
    sr_bw_opts.enforceLatencyBounds = True
    sr_bw_opts.createAdjSegmentHops = False
    sr_bw_opts.createLSPs = False

    sr_bw_opts.optInterfaces = [intf.rpc_key for intf in opt_intfs] if opt_intfs is not None else []
    sr_bw_opts.fixedLSPs = [lsp.rpc_key for lsp in fix_lsps] if fix_lsps is not None else []
    sr_bw_opts.fixedDemands = [demand.rpc_key for demand in fix_demands] if fix_demands is not None else []

    if logger:
        logger.debug("Run SRTEBWOptimizer with below parameters:")
        logger.debug(sr_bw_opts)

    res = sr_bw_opt.run(net.rpc_plan_network, sr_bw_opts)
    tool_mgr.removeTool(sr_bw_opt)

    net.model.cache_valid = False
    return res


def get_opm_obj(net, name):
    try:
        return net.model.nodes[name]
    except Exception:
        pass
    try:
        return net.model.interfaces[name]
    except Exception:
        pass
    try:
        return net.model.lsps[name]
    except Exception:
        return None


def exists(net, keystr):
    if get_opm_obj(net, keystr):
        return True
    return False


def get_lsp_segment_lists(net, lsp):
    segment_lists = []
    if lsp.ordered_lsp_paths:
        for lsp_path in lsp.ordered_lsp_paths:
            if lsp_path.segment_list and lsp_path.segment_list.hops:
                segment_list_hops = lsp_path.segment_list.hops
                interfaces = net.model.route_simulation.routes[lsp_path].interfaces
                segment_lists.append((lsp_path.path_option, segment_list_hops, interfaces))
    return segment_lists


def get_hops_ips(hops):
    ip_addresses = []
    for hop in hops:
        ip_address = get_hop_ip(hop)
        if ip_address is None:
            return None
        ip_addresses.append(ip_address)
    return ip_addresses


def get_ips_rpc_hops(net, ip_addresses):
    hops = []
    for ip_address in ip_addresses:
        hop = get_ip_rpc_hop(net, ip_address)
        if hop is None:
            return None
        hops.append(hop)
    return hops


def get_hop_ip(hop):
    ip_address = None
    if hop.node:
        if hop.node.ip_address:
            ip_address = hop.node.ip_address
    if hop.interface:
        if hop.interface.ip_addresses:
            ip_address = hop.interface.ip_addresses[0].split('/')[0]
    return ip_address


def get_ip_rpc_hop(net, ip_address):
    hop = None
    for node in net.model.nodes:
        if node.ip_address == ip_address:
            hop = new_segment_list_rpc_node_hop(node)
            break

    for interface in net.model.interfaces:
        if interface.ip_addresses:
            if interface.ip_addresses[0].split('/')[0] == ip_address:
                hop = new_segment_list_rpc_interface_hop(interface)
                break
    return hop


def get_ip_opm_obj(net, ip_address):
    obj = None
    for node in net.model.nodes:
        if node.ip_address == ip_address:
            obj = node
            break

    for interface in net.model.interfaces:
        if interface.ip_addresses:
            if interface.ip_addresses[0].split('/')[0] == ip_address:
                obj = interface
                break
    return obj


def new_segment_list_rpc_interface_hop(interface):
    hop = SegmentListHopRecord()
    hop.hopType = SegmentListHopType.SEGMENTLISTHOPTYPE_INTERFACE
    hop.ifaceHop = interface.rpc_key
    return hop


def new_segment_list_rpc_node_hop(node):
    hop = SegmentListHopRecord()
    hop.hopType = SegmentListHopType.SEGMENTLISTHOPTYPE_NODE
    hop.nodeHop = node.rpc_key
    return hop


def random_string(num):
    return ''.join(random.SystemRandom().choice(
        string.ascii_uppercase + string.digits
    ) for _ in range(num))


def log_input(logger, params):
    if logger.debug:
        content = {}
        input = {"input": content}
        _parse_params(params, content)
        logger.debug(json.dumps(input, indent=4))


def log_output(logger, params):
    if logger.debug:
        content = {}
        output = {"output": content}
        _parse_params(params, content)
        logger.debug(json.dumps(output, indent=4))


def _parse_params(node, content):
    if isinstance(node, ncs.maagic.Node):
        if isinstance(node, ncs.maagic.List):
            name = node._name
            sub_contents = []
            content[name] = sub_contents
            for child in node:
                sub_content = {}
                _parse_params(child, sub_content)
                sub_contents.append(sub_content)
        else:
            children = node._children
            for name in children.get_shortest_py_names():
                child = node.__getattr__(name)
                if isinstance(child, ncs.maagic.Node):
                    if isinstance(child, ncs.maagic.List):
                        _parse_params(child, content)
                        continue
                    sub_content = {}
                    content[name] = sub_content
                    _parse_params(child, sub_content)
                else:
                    if isinstance(child, ncs.maagic.Enum):
                        content[name] = str(child)
                    else:
                        content[name] = child


def read_nimo_last_successful_run(opm_action):
    with opm_action._in_read_transaction():
        status_kpstr = opm_action._kpstr[:(opm_action._kpstr.find('opm/'))] + 'nimo/status'
        try:
            status = ncs.maagic.get_node(opm_action._trans, status_kpstr)
        except Exception as e:
            opm_action.log.error(e)
            status = None
        if not status:
            return None
        return status.last_successful_run
