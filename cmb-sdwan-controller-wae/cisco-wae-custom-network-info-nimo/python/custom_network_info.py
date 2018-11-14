import ncs
from com.cisco.wae.opm.action import OpmActionBase
from com.cisco.wae.opm.action import OpmCapabilityRegistrar
from ncs.application import Application
from _namespaces.cisco_wae_custom_network_nimo_ns import ns
from common import *

# ---------------------------------------------
# COMPONENT THREAD THAT WILL BE STARTED BY WAE.
# ---------------------------------------------

class CustomNetworkNIMO(Application):

    def setup(self):
        self.log.info('Custom network info NIMO RUNNING')
        self.register_action(ns.actionpoint_customize_network_info_action_point,
                             CustomNetworkNIMOAction, [True])

    def teardown(self):
        self.log.info('Custom network info NIMO FINISHED')


class CustomNetworkNIMOCapabilities:
    '''
        The capabilities of this NIMO. These are the leaf level elements in
        the WAE Yang model that this NIMO can add, delete or modify.
    '''
    cap_tree = '''
        model
            nodes/node
                lsps/lsp
                    active-path
                interfaces/interface
                    capacity                    
        '''


class CustomNetworkNIMOAction(OpmActionBase):

    def init(self, init_args):
        '''
            Register the capabilities from this NIMO as collect-only.
        '''
        OpmCapabilityRegistrar.register_tree(
            action_prefix=ns.prefix,
            action_name=ns.actionpoint_customize_network_info_action_point,
            tree=CustomNetworkNIMOCapabilities.cap_tree,
            operation='collect_only')

    def run(self, net_name, input, output):
        self.update_start_status()

        src_net = self.get_nimo_source_network()

        with self.get_wae_network(src_net) as net:
            # A NIMO always gets its inputs from the 'advanced' node.
            intf_keystr_list = [str(interface) for interface in net.model.interfaces]
            lsp_keystr_list = [str(lsp) for lsp in net.model.lsps]
            new_service_class(net, 'Default')
            with self._in_read_transaction():
                advanced_node = self.get_config_node('advanced')
                self.log.info(advanced_node.operational_data_network)

                delt_ca = advanced_node.capacity_setting.default_capacity

                if delt_ca:
                    for intf in net.model.interfaces:
                        intf.configured_capacity = float(delt_ca)
                        self.log.debug("Interface {} capacity be set to {}.".format(str(intf), delt_ca))

                for special_intf in advanced_node.capacity_setting.interface_capacity:
                    intf = special_intf.interface
                    intf_ca = special_intf.capacity
                    if str(intf) in intf_keystr_list:
                        net.model.interfaces[str(intf)].configured_capacity = float(intf_ca)
                        self.log.debug("Interface {} capacity be set to {}.".format(str(intf), intf_ca))
                    else:
                        self.log.debug("Interface {} not exists in network.".format(str(intf)))

                root = ncs.maagic.get_root(self._trans)
                nodes = root.wae__networks.network[advanced_node.operational_data_network].model.nodes

                for node in nodes.node:
                    node_name = node.name
                    for lsp in node.lsps.lsp:
                        self.log.debug(lsp._path)
                        lsp_name = lsp.name
                        lsp_keystr = "lsp{" + node_name + "|" + lsp_name + "}"
                        if lsp_keystr in lsp_keystr_list:
                            for lsp_path in lsp.lsp_paths.lsp_path:
                                path_option = lsp_path.path_option
                                is_active = lsp_path.active
                                if is_active:
                                    net.model.lsps[lsp_keystr].active_path = path_option
                                    self.log.debug("Set LSP {} active path as {}".format(lsp_keystr, path_option))
                                    break
                                else:
                                    self.log.debug("No active path for LSP {}".format(lsp_keystr))
                        else:
                            self.log.debug("Skip set active path for LSP {}".format(lsp_keystr))
            #
            # with self.get_wae_network(advanced_node.operational_data_network) as oper_net:
            #     lsp_keystr_list = [str(lsp) for lsp in oper_net.model.lsps]
            #     for node in net.model.nodes:
            #         for lsp in node.lsps:
            #             if str(lsp) in lsp_keystr_list:
            #                 oper_lsp = oper_net.model.lsps[str(lsp.key)]
            #                 if oper_lsp.ordered_lsp_paths:
            #                     path_option = oper_lsp.ordered_lsp_paths[0].path_option
            #                     lsp.active_path = path_option
            #                     self.log.debug("Set LSP {} active path as {}".format(str(lsp), path_option))
            #                 else:
            #                     self.log.debug("No active path for LSP {}".format(str(lsp)))
            #             else:
            #                 self.log.debug("Skip set active path for LSP {}".format(str(lsp)))

            with self.get_wae_network(net_name) as orig_net:
                output.result = self.apply_model_differences(net_name, orig_net, net)
            # Update end status - active state as False and
            # last successful run time stamp depending on output.result.
            self.update_end_status(output.result)
