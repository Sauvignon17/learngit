import re

from com.cisco.wae.opm.action import OpmActionBase
from ncs.application import Application

from _namespaces.cisco_wae_opm_sr_lsp_bw_optimization_ns import ns as ns
from common import *

# regex for validation
INTERFACE_PATTERN = r'(?P<interface>^if\{.+\|.+\}$)'
LSP_PATTERN = r'(?P<lsp>^lsp\{.+\|.+\}$)'

# input error message
LSP_INVALID_ERROR = "[NG][INPUT]lsp invalid:{}."
INTERFACE_INVALID_ERROR = "[NG][INPUT]interfaces invalid:{}."
INTERFACE_CONFLICT_ERROR = "[NG][INPUT]interfaces conflict."


class SRLspBwOptimize(Application):

    def setup(self):
        self.log.debug('SR BW Optimization application RUNNING')
        self.register_action(ns.actionpoint_cisco_wae_opm_sr_lsp_bw_optimization_action_point,
                             SRLspBwOptimizeAction, [])

    def teardown(self):
        self.log.debug('SR BW Optimization application FINISHED')


class SRLspBwOptimizeAction(OpmActionBase):

    def run(self, net_name, input, output):
        log_input(self.log, input)

        last_run_time = read_nimo_last_successful_run(self)
        if last_run_time:
            output.network_info_timestamp = last_run_time

        with self.get_wae_network(net_name) as net:
            self.update_start_status()

            if not self._is_valid_input(net, input, output):
                log_output(self.log, output)
                return output

            # Create Default optimize group if no optimize-group configured
            self._create_default_optimize_group(net, input)

            global_util_threshold = float(max([g.max_util_percent for g in input.optimize_group]))
            opt_intfs = [net.model.interfaces[intf] for g in input.optimize_group for intf in g.interfaces.interface]
            opt_lsps, fix_lsps = self._classify_lsps(net, input)

            lsp_pri_demands = self._create_all_lsp_pri_demands(net)
            create_demand_mesh(net, self.log)
            demand_deduction(net, lsp_pri_demands, self.log)
            self.log.debug("Demands count after demand deduction: {}".format(len(net.model.demands)))

            dummy_demands = self._create_dummy_demands(net, input, global_util_threshold)
            self.log.debug("Demands count after adding dummy demands: {}".format(len(net.model.demands)))

            # fix demands on which when run sr bw optimization tool
            fix_demands = filter(lambda d: d not in lsp_pri_demands, net.model.demands)
            res = optimize_sr_bw(net=net, util_threshold=global_util_threshold, opt_intfs=opt_intfs, fix_lsps=fix_lsps,
                                 fix_demands=fix_demands, logger=self.log)
            self.log.debug("Optimize result: {}".format(str(res)))

            for demand in dummy_demands:
                if demand in net.model.demands:
                    net.model.demands.remove(demand)

            self.log.debug("Demands count after deleting dummy demands: {}".format(len(net.model.demands)))

            mod_lsp_cur_info_dict = {}
            for lsp_key in res.modifiedLSPs:
                lsp_name = 'lsp{' + lsp_key.sourceKey.name + '|' + lsp_key.name + '}'
                if input.recompute_optimized_lsp_inactive_path:
                    self._recompute_lsp_path(net, lsp_name)
                mod_lsp_cur_info_dict[lsp_name] = LspInfo(net=net, lsp=net.model.lsps[lsp_name])

            self.log.debug("Modified LSPs: {}".format(str(mod_lsp_cur_info_dict)))
            self._construct_output(net, input, output, mod_lsp_cur_info_dict)

            log_output(self.log, output)

            self.update_end_status(output.result)

    def _recompute_lsp_path(self, net, lsp_name):
        lsp = net.model.lsps[lsp_name]
        if lsp.ordered_lsp_paths:
            recompute_paths = []
            active_path_opt = lsp.active_path if lsp.active_path else min(
                map(lambda path: path.path_option, lsp.ordered_lsp_paths))
            active_path = filter(lambda path: path.path_option == active_path_opt, lsp.ordered_lsp_paths)[0]
            self.log.debug("Modified LSP active path:{}".format(str(active_path)))

            confirmed_intfs = []
            if active_path.segment_list:
                active_path_intfs = net.model.route_simulation.routes[active_path].interfaces
                self.log.debug("Modified LSP active path {} interfaces: {}".format(str(active_path),
                                                                                   str(active_path_intfs)))
                if active_path_intfs:
                    confirmed_intfs.extend(active_path_intfs)
            else:
                return

            for i in range(0, len(lsp.ordered_lsp_paths)):
                inactive_path = lsp.ordered_lsp_paths[i]

                if inactive_path.path_option == active_path_opt:
                    continue

                if not inactive_path.segment_list:
                    continue

                inactive_path_intfs = net.model.route_simulation.routes[inactive_path].interfaces
                self.log.debug("Modified LSP inactive path {} interfaces: {}".format(str(inactive_path),
                                                                                     str(inactive_path_intfs)))
                if not inactive_path_intfs:
                    continue

                intersection = [intf for intf in inactive_path_intfs if intf in confirmed_intfs]
                self.log.debug(
                    "Modified LSP the intersection between inactive path {} interfaces and confirmed interfaces: {}".format(
                        str(inactive_path),
                        str(intersection)))
                if intersection:
                    recompute_paths.append(inactive_path)
                else:
                    confirmed_intfs.extend(inactive_path_intfs)

            for recompute_path in recompute_paths:
                self.log.debug("Modified LSP inactive path {} will be recomputed".format(str(recompute_path)))
                new_path = LspPath(net=net, source=lsp.source, destination=lsp.destination,
                                   avoid_intfs=confirmed_intfs, logger=self.log)

                path_rpc_object = recompute_path.segment_list.rpc_object
                path_rpc_object.removeAllHops()
                if new_path.segment_list_rpc_hops:
                    path_rpc_object.addHops(new_path.segment_list_rpc_hops)
                    self.log.debug(
                        "Modified LSP inactive path {} has successfully computed".format(str(recompute_path)))
                else:
                    self.log.debug(
                        "Modified LSP inactive path {} computation failed,set segment to empty".format(
                            str(recompute_path)))
                new_intfs = net.model.route_simulation.routes[recompute_path].interfaces
                confirmed_intfs.extend(new_intfs)

    def _is_valid_input(self, net, input, output):
        error_msg = self._check_data_integrity(input, net)
        if not error_msg:
            error_msg = self._check_data_logic(input)
        if error_msg:
            output.result = False
            output.error_message = error_msg
            self.log.error(error_msg)
            return False

        return True

    def _create_default_optimize_group(self, net, input):
        if len(input.optimize_group) == 0:
            og = input.optimize_group.create()
            og.name = "DEFAULT"
            og.max_util_percent = input.max_util_percent
            og.interfaces.interface = [str(interface) for interface in net.model.interfaces]
            self.log.debug("DEFAULT optimize group will be applied, all interfaces are target.")
        log_input(self.log, input)

    def _create_dummy_demands(self, net, input, global_util_threshold):
        dummy_demands = []
        # create dummy demands
        for group in input.optimize_group:
            util_threshold = group.max_util_percent
            delta_utilization = float(global_util_threshold) - float(util_threshold)
            for intf in group.interfaces.interface:
                interface = net.model.interfaces[intf]
                delta_traffic = interface.simulated_capacity * (delta_utilization / 100)
                if delta_traffic == 0:
                    continue
                routed_circuits = filter(
                    lambda x: (x.interface_a == interface) or (x.interface_b == interface),
                    net.model.circuits)

                if not routed_circuits or len(routed_circuits) != 1:
                    raise IndexError()

                if routed_circuits[0].interface_a == interface:
                    source = routed_circuits[0].interface_b
                else:
                    source = routed_circuits[0].interface_a
                destination = interface
                dummy_demand = new_demand(net, source, destination)
                dummy_demand.active = True
                dummy_demand.traffic = delta_traffic

                dummy_demands.append(dummy_demand)
        self.log.debug("Dummy demands count: {}".format(len(net.model.demands)))
        return dummy_demands

    def _create_all_lsp_pri_demands(self, net):
        lsp_private_demands = []
        for lsp in net.model.lsps:
            lsp.private = True
            if lsp.source and lsp.destination:
                lsp_demand = new_demand(net, lsp.source.name, lsp.destination.name)
                lsp_demand.active = True
                lsp_demand.traffic = lsp.measured_traffic if lsp.measured_traffic else 0.0
                lsp_demand.private_lsp = lsp
                lsp_private_demands.append(lsp_demand)
        self.log.debug("Lsp private demands count: {}".format(len(net.model.demands)))
        return lsp_private_demands

    def _classify_lsps(self, net, input):
        opt_lsps = []
        fix_lsps = []

        if input.lsps:
            if input.lsps.method == 'fix':
                for lsp in net.model.lsps:
                    if str(lsp) in input.lsps.lsp:
                        fix_lsps.append(lsp)
                    else:
                        opt_lsps.append(lsp)
            else:
                for lsp in net.model.lsps:
                    if str(lsp) in input.lsps.lsp:
                        opt_lsps.append(lsp)
                    else:
                        fix_lsps.append(lsp)
        else:
            opt_lsps.extend([lsp for lsp in net.model.lsps])
        self.log.debug("Opt LSPs: {}".format(str(opt_lsps)))
        self.log.debug("Fix LSPs: {}".format(str(fix_lsps)))
        return opt_lsps, fix_lsps

    @staticmethod
    def _construct_output(net, input, output, mod_lsp_cur_info_dict):
        output.result = True
        for group in input.optimize_group:
            target = round(float(group.max_util_percent), 2)
            og = output.optimize_group.create()
            og.name = group.name
            og.target_utilization = target
            for intf_name in group.interfaces.interface:
                interface = net.model.interfaces[intf_name]
                util_before = round(float(interface.measured_utilization if interface.measured_utilization else 0.0), 2)
                util_after = round(float(interface.simulated_utilization), 2)
                state = True if util_after <= target else False
                o_intf = og.interface.create()
                o_intf.name = intf_name
                o_intf.util_before = util_before
                o_intf.util_after = util_after
                o_intf.state = state
                if output.result and not state:
                    output.result = False
        for key, value in mod_lsp_cur_info_dict.items():
            mod_lsp = output.modified_lsp.create()
            mod_lsp.name = key
            for segment_list in value.segment_lists:
                path = mod_lsp.lsp_path.create()
                path.path_option = segment_list[0]
                path.segment_list = get_hops_ips(segment_list[1])
                path.interfaces = segment_list[2]

    @staticmethod
    def _check_data_logic(input):
        error_msg = []
        intfs_keystr = [intf for g in input.optimize_group for intf in g.interfaces.interface]
        if len(set(intfs_keystr)) != len(intfs_keystr):
            error_msg.append(INTERFACE_CONFLICT_ERROR)
        return error_msg

    @staticmethod
    def _check_data_integrity(input, net):
        error_msg = []
        group_intf_err_dict = {}
        lsp_err_list = []
        for group in input.optimize_group:
            intf_err_list = []
            for intf_keystr in group.interfaces.interface:
                match_group = re.match(INTERFACE_PATTERN, intf_keystr, re.M | re.I)
                if not match_group or not exists(net, intf_keystr):
                    intf_err_list.append(intf_keystr)
            if intf_err_list:
                group_intf_err_dict[group.name] = intf_err_list
        if input.lsps:
            for lsp_keystr in input.lsps.lsp:
                match_group = re.match(LSP_PATTERN, lsp_keystr, re.M | re.I)
                if not match_group or not exists(net, lsp_keystr):
                    lsp_err_list.append(lsp_keystr)

        if group_intf_err_dict:
            error_msg.append(INTERFACE_INVALID_ERROR.format(str(group_intf_err_dict)))
        if lsp_err_list:
            error_msg.append(LSP_INVALID_ERROR.format(str(lsp_err_list)))
        return error_msg
