from com.cisco.wae.opm.action import OpmActionBase
from com.cisco.wae.opm.network.model.interface.netobject import Interface
from com.cisco.wae.opm.network.model.node.netobject import Node
from ncs.application import Application

from _namespaces.cisco_wae_opm_sr_lsp_path_initialization_ns import ns
from common import *

# ---------------------------------------------
# COMPONENT THREAD THAT WILL BE STARTED BY WAE.
# ---------------------------------------------

# interface or node regex pattern.
PATTERN = r'((?P<interface>^if\{.+\|.+\}$)|(?P<node>^node\{.+\}$))'

# input error
PATH_SRC_INVALID_ERROR = \
    "[NG][INPUT]source-node invalid:{}."
PATH_DES_INVALID_ERROR = \
    "[NG][INPUT]destination-node invalid:{}."
PATH_SRC_DES_SAME_ERROR = \
    "[NG][INPUT]source-node: {} and destination-node: {} are same."
PATH_SEGMENT_LIST_INVALID_ERROR = \
    "[NG][INPUT]segment-list invalid:{}."

# path computation result message
PATH_INITIALIZATION_FAIL_MESSAGE = \
    "[NG]Fail in path initializing,No path could be found."
PATH_INITIALIZATION_FAIL_UNKNOWN_MESSAGE = \
    "[NG]Fail in path initializing,unknown error."


# PATH_COMPUTATION_ERROR_MESSAGE = \
#    "[Error][INTERNAL] in {} path computing caused by segment-list or interfaces not exists."


class SRLspPathInitialize(Application):

    def setup(self):
        self.log.info('Path init SR TE application RUNNING')
        self.register_action(ns.actionpoint_wae_opm_sr_lsp_path_init_action_point,
                             SRLspPathInitAction, [])

    def teardown(self):
        self.log.info('Path init SR TE application FINISHED')


class SRLspPathInitAction(OpmActionBase):

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

            segment_list_rpc_hops = get_ips_rpc_hops(net, input.segment_list)
            path = LspPath(net=net, source=src_node, destination=des_node, logger=self.log)

            error_message = []
            if path.reset_segment_list_hops(segment_list_rpc_hops):
                if path.segment_list_hops and path.interfaces:
                    output.result = True
                    output.segment_list = path.segment_list_hops_ip
                    output.interfaces = path.interfaces
                else:
                    error_message.append(PATH_INITIALIZATION_FAIL_UNKNOWN_MESSAGE)
                    output.result = False
            else:
                error_message.append(PATH_INITIALIZATION_FAIL_MESSAGE)
                output.result = False

            if not output.result:
                output.error_message = error_message

            log_output(self.log, output)

            self.update_end_status(output.result)

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

    @staticmethod
    def _check_data_logic(input, net):
        error_msg = []
        src = get_opm_obj(net, input.source_node)
        des = get_opm_obj(net, input.destination_node)
        entries = [get_ip_opm_obj(net, ip) for ip in input.segment_list]
        for i in range(0, len(entries)):
            cur = entries[i]

            # not last hop
            if i != len(entries) - 1:
                if isinstance(cur, Node):
                    if cur == src:
                        error_msg.append(
                            PATH_SEGMENT_LIST_INVALID_ERROR.format(
                                "Node hop({}) must be different from source node.".format(input.segment_list[i])))
                        break
                    if cur == des:
                        error_msg.append(
                            PATH_SEGMENT_LIST_INVALID_ERROR.format(
                                "Node hop({}) must be different from destination node.".format(input.segment_list[i])))
                        break
            # last hop
            else:
                if isinstance(cur, Node):
                    if cur == src:
                        error_msg.append(
                            PATH_SEGMENT_LIST_INVALID_ERROR.format(
                                "Node hop({}) must be different from source node.".format(input.segment_list[i])))
                        break

            # first hop
            if i == 0:
                if isinstance(cur, Interface):
                    if cur.node != src:
                        error_msg.append(
                            PATH_SEGMENT_LIST_INVALID_ERROR.format(
                                "First hop({}) is interface,must belong to source node.".format(input.segment_list[i])))
                        break
                continue

            pre = entries[i - 1]

            if isinstance(cur, Interface):
                if isinstance(pre, Interface):
                    routed_circuits = filter(lambda x: (x.interface_a == pre) or (x.interface_b == pre),
                                             net.model.circuits)
                    if routed_circuits[0].interface_a == pre:
                        pre_remote_intf = routed_circuits[0].interface_b
                    else:
                        pre_remote_intf = routed_circuits[0].interface_a
                    if pre_remote_intf.node != cur.node:
                        error_msg.append(PATH_SEGMENT_LIST_INVALID_ERROR.format(
                            "Interface hop({}) is unreachable".format(input.segment_list[i])))
                        break
                else:
                    if cur.node != pre:
                        error_msg.append(PATH_SEGMENT_LIST_INVALID_ERROR.format(
                            "Interface hop({}) is unreachable".format(input.segment_list[i])))
                        break

        return error_msg

    @staticmethod
    def _check_data_integrity(input, net):
        error_msg = []

        ip_err_list = []

        src = get_opm_obj(net, input.source_node)
        des = get_opm_obj(net, input.destination_node)

        if not src or not isinstance(src, Node):
            error_msg.append(PATH_SRC_INVALID_ERROR.format(input.source_node))
        if not des or not isinstance(des, Node):
            error_msg.append(PATH_DES_INVALID_ERROR.format(input.destination_node))
        if src == des:
            error_msg.append(PATH_SRC_DES_SAME_ERROR.format(input.source_node, input.destination_node))

        ips = [hop for hop in input.segment_list]
        id_ips = set(ips)
        dup_ips = [ip for ip in ips if ip not in id_ips]

        if dup_ips:
            error_msg.append(PATH_SEGMENT_LIST_INVALID_ERROR.format("Duplicated ips in segment-list" +
                                                                    str(dup_ips)))

        for ip in input.segment_list:
            opm_obj = get_ip_opm_obj(net, ip)
            if not opm_obj:
                ip_err_list.append(ip)

        if ip_err_list:
            error_msg.append(
                PATH_SEGMENT_LIST_INVALID_ERROR.format("Hop corresponding to ip not exists" + str(ip_err_list)))
        return error_msg
