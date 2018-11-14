import re

from _namespaces.cisco_wae_opm_sr_lsp_path_computation_ns import ns
from com.cisco.wae.opm.action import OpmActionBase
from com.cisco.wae.opm.network.model.interface.netobject import Interface
from com.cisco.wae.opm.network.model.node.netobject import Node
from ncs.application import Application

from common import *

# ---------------------------------------------
# COMPONENT THREAD THAT WILL BE STARTED BY WAE.
# ---------------------------------------------

# constants
PRIMARY = "primary"
BACKUP = "backup"
INCLUSION = "inclusion"
AVOIDANCE = "avoidance"
OK = "OK"
NG = "NG"

# interface or node regex pattern
PATTERN = r'((?P<interface>^if\{.+\|.+\}$)|(?P<node>^node\{.+\}$))'

# input error
PATH_SRC_INVALID_ERROR = \
    "[NG][INPUT]source-node invalid:{}."
PATH_DES_INVALID_ERROR = \
    "[NG][INPUT]destination-node invalid:{}."
PATH_CONSTRAINTS_INVALID_ERROR = \
    "[NG][INPUT]{} path {} constraints invalid:{}."
PATH_SRC_DES_SAME_ERROR = \
    "[NG][INPUT]source-node {} and destination-node {} are same."
PATH_SRC_DES_CONSTRAINTS_CONFLICT_ERROR = \
    "[NG][INPUT]there is a conflict between source-node or destination-node and {} path {} constraints."
PATH_CONSTRAINTS_CONFLICT_ERROR = \
    "[NG][INPUT]there is a conflict between {} path inclusion and avoidance constraints."
PATH_CONSTRAINTS_AFFINITY_CONFLICT_ERROR = \
    "[NG][INPUT]there is a conflict between {} path affinity-inclusion and affinity-avoidance constraints."

# path computation result message
PATH_COMPUTATION_NO_PATH_FAIL_MESSAGE = \
    "[NG]Fail in {} path computing,No TE path could be found."
PATH_COMPUTATION_TRANSFER_TO_IP_FAIL_MESSAGE = \
    "[NG]Fail in {} path computing,TE path found,but could not transfer to ips expression."
PATH_COMPUTATION_GET_INTERFACES_FAIL_MESSAGE = \
    "[NG]Fail in {} path computing,TE path found,but could not get routed interfaces."


# PATH_COMPUTATION_ERROR_MESSAGE = \
#    "[Error][INTERNAL] in {} path computing caused by segment-list or interfaces not exists."


class SRLspPathCompute(Application):

    def setup(self):
        self.log.info('Path Compute SR TE application RUNNING')
        self.register_action(ns.actionpoint_wae_opm_sr_lsp_path_compute_action_point,
                             SRLspPathComputeAction, [])

    def teardown(self):
        self.log.info('Path Compute SR TE application FINISHED')


class SRLspPathComputeAction(OpmActionBase):

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

            src_node = get_opm_obj(net, input.source_node)
            des_node = get_opm_obj(net, input.destination_node)

            # Format primary path constraints
            pri_path_cstrs = self._fmt_path_cstrs(net, input.primary_path)
            self.log.debug(pri_path_cstrs)

            # Compute primary path LSP
            pri_path = self._sr_path_compute(net, src_node, des_node, input.metric, pri_path_cstrs)

            error_message = []
            output.result = True
            if pri_path and pri_path.segment_list_hops_ip and pri_path.interfaces:
                output.primary_path.segment_list = pri_path.segment_list_hops_ip
                output.primary_path.interfaces = pri_path.interfaces
                output.primary_path.state = True
            else:
                output.result = False
                if not pri_path:
                    error_message.append(PATH_COMPUTATION_NO_PATH_FAIL_MESSAGE.format(PRIMARY))
                else:
                    if not pri_path.segment_list_hops_ip:
                        error_message.append(PATH_COMPUTATION_TRANSFER_TO_IP_FAIL_MESSAGE.format(PRIMARY))
                    if not pri_path.interfaces:
                        error_message.append(PATH_COMPUTATION_GET_INTERFACES_FAIL_MESSAGE.format(PRIMARY))
                output.primary_path.state = False

            if input.backup_path and output.result is True:
                bak_path_cstrs = self._fmt_path_cstrs(net, input.backup_path, pri_path.interfaces)
                bak_path = self._sr_path_compute(net, src_node, des_node, input.metric, bak_path_cstrs)
                output.backup_path.create()
                if bak_path and bak_path.segment_list_hops_ip and bak_path.interfaces:
                    output.backup_path.segment_list = bak_path.segment_list_hops_ip
                    output.backup_path.interfaces = bak_path.interfaces
                    output.backup_path.state = True
                else:
                    output.result = False
                    if not bak_path:
                        error_message.append(PATH_COMPUTATION_NO_PATH_FAIL_MESSAGE.format(BACKUP))
                    else:
                        if not bak_path.segment_list_hops_ip:
                            error_message.append(PATH_COMPUTATION_TRANSFER_TO_IP_FAIL_MESSAGE.format(BACKUP))
                        if not bak_path.interfaces:
                            error_message.append(PATH_COMPUTATION_GET_INTERFACES_FAIL_MESSAGE.format(BACKUP))
                        output.backup_path.state = False

            if not output.result:
                output.error_message = error_message

            log_output(self.log, output)

            self.update_end_status(output.result)

    def _sr_path_compute(self, net, src_node, des_node, metric, cstrs):
        main_path_segment_list_rpc_hops = []
        must_pass_entry, avoid_nodes, avoid_intfs = self._parse_cstrs(net, src_node, des_node, cstrs)

        for index in range(0, len(must_pass_entry)):
            if index is len(must_pass_entry) - 1:
                break
            sub_avoid_nodes = []
            sub_avoid_intfs = []
            sub_src = must_pass_entry[index]
            sub_des = must_pass_entry[index + 1]

            self.log.debug("Start computing sub-path from {} to {}.".format(str(sub_src), str(sub_des)))

            sub_src_node = sub_src.node if isinstance(sub_src, Interface) else sub_src
            sub_des_node = sub_des.node if isinstance(sub_des, Interface) else sub_des

            # Ignore current sub path computation if source and destination are equal
            if str(sub_src_node) == str(sub_des_node):
                self.log.debug(
                    "Skip process cause by source node {} and destination node are same.".format(str(sub_src),
                                                                                                 str(sub_des)))
                continue

            avoid_entry_keystr_list = [str(entry) for entry in avoid_nodes + avoid_intfs]
            if str(sub_src) in avoid_entry_keystr_list or str(sub_des) in avoid_entry_keystr_list:
                self.log.debug(
                    "Sub-path can't be found,source or destination be avoided in path computation.")
                self.log.debug("Avoidance entries:{}".format(avoid_entry_keystr_list))
                return None

            # Find all avoidance entries for all sub-path computation
            sub_avoid_nodes.extend(avoid_nodes)
            sub_avoid_intfs.extend(avoid_intfs)

            # Find all avoidance entries just for current sub-path computation
            sub_avoid_entries = filter(lambda x: str(x) != str(sub_src) and str(x) != str(sub_des), must_pass_entry)

            # Classify the sub-path avoidance entries
            for entry in sub_avoid_entries:
                if isinstance(entry, Node) and entry not in sub_avoid_nodes:
                    sub_avoid_nodes.append(entry)
                elif isinstance(entry, Interface) and entry not in sub_avoid_intfs:
                    sub_avoid_intfs.append(entry)

            # Init sub-path segment list hops
            sub_path_segment_list_rpc_hops = []

            # Use adj-sid if src is interface and
            if isinstance(sub_src, Interface):
                hop = new_segment_list_rpc_interface_hop(sub_src)
                sub_path_segment_list_rpc_hops.append(hop)

                remote_intf = None
                for circuit in net.model.circuits:
                    if str(circuit.interface_a) == str(sub_src):
                        remote_intf = circuit.interface_b
                        break
                    if str(circuit.interface_b) == str(sub_src):
                        remote_intf = circuit.interface_a
                        break
                if not remote_intf:
                    return None

                if str(sub_des_node) == str(remote_intf.node):
                    self.log.debug("Skip process cause by source interface connected to destination node directly.")
                    main_path_segment_list_rpc_hops.extend(sub_path_segment_list_rpc_hops)
                    continue
                else:
                    if must_pass_entry[index] not in sub_avoid_intfs:
                        sub_avoid_intfs.append(must_pass_entry[index])
                    sub_src = remote_intf.node

            # Use node as des if des is interface
            if isinstance(sub_des, Interface):
                if must_pass_entry[index + 1] not in sub_avoid_intfs:
                    sub_avoid_intfs.append(must_pass_entry[index + 1])
                sub_des = sub_des.node
            sub_path = LspPath(net=net, source=sub_src, destination=sub_des, metric=metric, avoid_nodes=sub_avoid_nodes,
                               avoid_intfs=sub_avoid_intfs, logger=self.log)

            if sub_path.segment_list_rpc_hops:
                sub_path_segment_list_rpc_hops.extend(sub_path.segment_list_rpc_hops)
            else:
                self.log.debug("Sub-path can't be found.")
                return None

            main_path_segment_list_rpc_hops.extend(sub_path_segment_list_rpc_hops)

        # Create LSP from source node to destination node
        main_path = LspPath(net=net, source=src_node, destination=des_node, metric=metric, logger=self.log)
        if main_path.reset_segment_list_hops(main_path_segment_list_rpc_hops):
            if main_path.segment_list_hops:
                return main_path
        return None

    def _is_valid_input(self, net, input, output):
        error_msg = self._check_data_integrity(input, net)
        if not error_msg:
            error_msg = self._check_data_logic(input, net)

        if error_msg:
            output.result = False
            output.error_message = error_msg
            self.log.error(error_msg)
            return False

        return True

    def _check_data_logic(self, input, net):
        error_msg = []

        if input.primary_path.constraints:
            self._check_constraints(net, input, error_msg, PRIMARY)

        if input.backup_path.constraints:
            self._check_constraints(net, input, error_msg, BACKUP)

        return error_msg

    def _check_constraints(self, net, input, error_msg, type):
        src_keystr = str(get_opm_obj(net, input.source_node))
        des_keystr = str(get_opm_obj(net, input.destination_node))

        if type == PRIMARY:
            inclusion = input.primary_path.constraints.inclusion
            avoidance = input.primary_path.constraints.avoidance
            affinity_inclusion = input.primary_path.constraints.affinity_inclusion
            affinity_avoidance = input.primary_path.constraints.affinity_avoidance
        else:
            inclusion = input.backup_path.constraints.inclusion
            avoidance = input.backup_path.constraints.avoidance
            affinity_inclusion = input.backup_path.constraints.affinity_inclusion
            affinity_avoidance = input.backup_path.constraints.affinity_avoidance

        if inclusion:
            if len(set(inclusion + [src_keystr, des_keystr]) - set(inclusion)) != 2:
                error_msg.append(PATH_SRC_DES_CONSTRAINTS_CONFLICT_ERROR.format(type, INCLUSION))
        if avoidance:
            if len(set(avoidance + [src_keystr, des_keystr]) - set(avoidance)) != 2:
                error_msg.append(PATH_SRC_DES_CONSTRAINTS_CONFLICT_ERROR.format(type, AVOIDANCE))
        if inclusion and avoidance:
            if len(set(inclusion + avoidance)) != len(inclusion + avoidance):
                error_msg.append(PATH_CONSTRAINTS_CONFLICT_ERROR.format(type))
            pri_in_intf_nodes = map(lambda i: str(i.node),
                                    filter(lambda x: isinstance(x, Interface),
                                           [get_opm_obj(net, i) for i in inclusion]))
            self.log.debug(pri_in_intf_nodes)
            if len(set(pri_in_intf_nodes) - set(avoidance)) != len(set(pri_in_intf_nodes)):
                error_msg.append(PATH_CONSTRAINTS_CONFLICT_ERROR.format(type))

        if affinity_inclusion and affinity_avoidance:
            if len(affinity_inclusion + affinity_avoidance) != len(set(affinity_inclusion + affinity_avoidance)):
                error_msg.append(PATH_CONSTRAINTS_AFFINITY_CONFLICT_ERROR.format(type))

    @staticmethod
    def _check_data_integrity(input, net):
        error_msg = []

        pri_in_err_list = []
        pri_avoid_err_list = []
        bak_in_err_list = []
        bak_avoid_err_list = []

        src = get_opm_obj(net, input.source_node)
        des = get_opm_obj(net, input.destination_node)

        if not src or not isinstance(src, Node):
            error_msg.append(PATH_SRC_INVALID_ERROR.format(input.source_node))
        if not des or not isinstance(des, Node):
            error_msg.append(PATH_DES_INVALID_ERROR.format(input.destination_node))
        if str(src) == str(des):
            error_msg.append(PATH_SRC_DES_SAME_ERROR.format(input.source_node, input.destination_node))

        if input.primary_path.constraints:
            inclusion = input.primary_path.constraints.inclusion
            avoidance = input.primary_path.constraints.avoidance
            if inclusion:
                for i in inclusion:
                    match_group = re.match(PATTERN, i, re.M | re.I)
                    if not match_group or not exists(net, i):
                        pri_in_err_list.append(i)
            if avoidance:
                for i in avoidance:
                    match_group = re.match(PATTERN, i, re.M | re.I)
                    if not match_group or not exists(net, i):
                        pri_avoid_err_list.append(i)
        if input.backup_path.constraints:
            inclusion = input.backup_path.constraints.inclusion
            avoidance = input.backup_path.constraints.avoidance
            if inclusion:
                for i in inclusion:
                    match_group = re.match(PATTERN, i, re.M | re.I)
                    if not match_group or not exists(net, i):
                        bak_in_err_list.append(i)
            if avoidance:
                for i in avoidance:
                    match_group = re.match(PATTERN, i, re.M | re.I)
                    if not match_group or not exists(net, i):
                        bak_avoid_err_list.append(i)
        if pri_in_err_list:
            error_msg.append(PATH_CONSTRAINTS_INVALID_ERROR.format(PRIMARY, INCLUSION, str(pri_in_err_list)))
        if pri_avoid_err_list:
            error_msg.append(PATH_CONSTRAINTS_INVALID_ERROR.format(PRIMARY, AVOIDANCE, str(pri_avoid_err_list)))
        if bak_in_err_list:
            error_msg.append(PATH_CONSTRAINTS_INVALID_ERROR.format(BACKUP, INCLUSION, str(bak_in_err_list)))
        if bak_avoid_err_list:
            error_msg.append(PATH_CONSTRAINTS_INVALID_ERROR.format(BACKUP, AVOIDANCE, str(bak_avoid_err_list)))

        return error_msg

    @staticmethod
    def _fmt_path_cstrs(net, path, ex_avoid_entries=None):
        if not ex_avoid_entries:
            ex_avoid_entries = []
        cstrs = path.constraints
        if cstrs:
            incl = cstrs.inclusion
            avd = cstrs.avoidance
            aff_incl = cstrs.affinity_inclusion
            aff_avd = cstrs.affinity_avoidance
            dd_bw = cstrs.demand_bandwidth
            path_cstrs_dict = {
                "inclusion": [get_opm_obj(net, keystr) for keystr in
                              incl] if incl else [],
                "avoidance": ([get_opm_obj(net, keystr) for keystr in
                               avd] if avd else []) + ex_avoid_entries,
                "affinity_inclusion": aff_incl if aff_incl else [],
                "affinity_avoidance": aff_avd if aff_avd else [],
                "demand_bandwidth": float(dd_bw) if dd_bw else 0.0
            }
            return path_cstrs_dict
        else:
            path_cstrs_dict = {
                "inclusion": [],
                "avoidance": ex_avoid_entries,
                "affinity_inclusion": [],
                "affinity_avoidance": [],
                "demand_bandwidth": 0.0
            }
            return path_cstrs_dict

    @staticmethod
    def _parse_cstrs(net, src_node, des_node, constraints):
        def interface_filter(avoidance):
            affinity_inclusion = None
            affinity_avoidance = None
            demand_bandwidth = constraints['demand_bandwidth']

            if constraints['affinity_inclusion']:
                affinity_inclusion = set(constraints['affinity_inclusion'])
            if constraints['affinity_avoidance']:
                affinity_avoidance = set(constraints['affinity_avoidance'])

            for interface in net.model.interfaces:
                if interface in avoidance:
                    continue
                intf_afs = set(interface.affinities) if interface.affinities else set([])
                if affinity_inclusion:
                    if not affinity_inclusion.issubset(intf_afs):
                        avoidance.append(interface)
                        continue
                if affinity_avoidance:
                    if intf_afs - affinity_avoidance != intf_afs:
                        avoidance.append(interface)
                        continue

                capacity = round(interface.configured_capacity if interface.configured_capacity else 0.0, 2)
                measured = round(interface.measured_traffic if interface.measured_traffic else 0.0, 2)
                demand = round(float(demand_bandwidth), 2)
                if demand_bandwidth:
                    if capacity - measured < demand:
                        avoidance.append(interface)
            return avoidance

        avoid_nodes = []
        avoid_intfs = []
        must_pass_entry = [src_node]
        for entry in constraints['inclusion']:
            must_pass_entry.append(entry)
        must_pass_entry.append(des_node)

        for entry in constraints['avoidance']:
            if isinstance(entry, Node):
                avoid_nodes.append(entry)
            elif isinstance(entry, Interface):
                avoid_intfs.append(entry)

        interface_filter(avoid_intfs)

        return must_pass_entry, avoid_nodes, avoid_intfs
