import Queue
import time
from copy import copy
from itertools import groupby
from com.cisco.wae.opm.network import open_plan


class Link(object):
    def __init__(self, local_interface, remote_interface, delay):
        self.local_node = local_interface.node
        self.local_interface = local_interface
        self.remote_node = remote_interface.node
        self.remote_interface = remote_interface
        self.te_metric = local_interface.te_metric
        self.igp_metric = local_interface.igp_metric
        self.delay = delay
        self.remain_bw = local_interface.configured_capacity - local_interface.measured_traffic if local_interface.measured_traffic else 0

    def pair(self):
        return self.local_interface, self.remote_interface

    def __str__(self):
        return str(self.local_interface.key) + '->' + str(self.remote_interface.key)


class TraversalBranch(object):
    def __init__(self, start_node, end_node, links, parent, cost):
        self.start_node = start_node
        self.end_node = end_node
        self.links = links
        self.parent = parent
        self.last_rel = None if len(links) == 0 else links[-1]
        self.cost = cost

    def __cmp__(self, other):
        return cmp(self.cost, other.cost)


class BranchExpander(object):
    def __init__(self, src_node, des_node, links, attr):
        self.src_node = src_node
        self.des_node = des_node
        self.links = links
        self.attr = attr
        self.link_dic = dict([(key, list(group)) for key, group in
                              groupby(sorted(self.links, key=lambda x: x.local_node.name),
                                      key=lambda x: x.local_node.name)])
        self.best_so_far = None

    def expand(self, branch):
        # do not expand if branch end_node equals des_node
        if branch.end_node.name == self.des_node.name:
            self.best_so_far = branch.cost if self.best_so_far is None or branch.cost < self.best_so_far else self.best_so_far
            return
        # find the rels that the start_node is the branch end_node
        next_links = None
        if self.link_dic.has_key(branch.end_node.name):
            next_links = self.link_dic[branch.end_node.name]

        if next_links is None:
            return
        for link in next_links:
            new_links = copy(branch.links)
            new_links.append(link)
            cost = reduce(lambda x, y: x + y, map(lambda l:l.__getattribute__(self.attr) if l.__getattribute__(self.attr) else 0.0,
                                                  new_links), 0.0)
            if self.best_so_far is not None and cost > self.best_so_far:
                continue
            yield TraversalBranch(start_node=branch.start_node, end_node=link.remote_node,
                                  links=new_links, parent=branch, cost=cost)


# BranchSelector
class BranchSelector(object):
    def __init__(self):
        self.pri_dict = {}
        self.queue = Queue.PriorityQueue()
        self.visited = []

    def put(self, branch):
        if not self.pri_dict.has_key(branch.end_node.name) or self.pri_dict[branch.end_node.name] is None:
            self.pri_dict[branch.end_node.name] = [branch]
            self.queue.put(branch)
        else:
            old_branches = self.pri_dict[branch.end_node.name]
            if branch < old_branches[0]:
                self.pri_dict[branch.end_node.name] = [branch]
                self.queue.put(branch)
            elif branch == old_branches[0]:
                self.pri_dict[branch.end_node.name].append(branch)
                self.queue.put(branch)

    def select(self):
        while True:
            try:
                candidate_branch = self.queue.get_nowait()
                # check the node whether visited, avoid loop
                if candidate_branch.end_node in self.visited:
                    continue
                else:
                    # add branch end_node to visited list
                    if candidate_branch.end_node not in [un_b.end_node for un_b in self.queue.queue]:
                        self.visited.append(candidate_branch.end_node)
                    return candidate_branch
            except Queue.Empty:
                return None


def extract_paths(network, lsp, attr):
    src_node = lsp.source
    des_node = lsp.destination

    all_circuits = network.model.circuits
    route = network.model.route_simulation.routes[lsp]

    routed_interfaces = route.interfaces
    # filter all used circuits
    routed_circuits = filter(lambda x: (x.interface_a in routed_interfaces) or (x.interface_b in routed_interfaces),
                             all_circuits)
    # construct links depends on used interfaces and circuits
    links = []
    for interface in routed_interfaces:
        for circuit in routed_circuits:
            if interface == circuit.interface_a:
                links.append(Link(circuit.interface_a, circuit.interface_b, circuit.delay))
            elif interface == circuit.interface_b:
                links.append(Link(circuit.interface_b, circuit.interface_a, circuit.delay))
            else:
                pass

    branch_expander = BranchExpander(src_node=src_node, des_node=des_node, links=links, attr=attr)
    init_branch = TraversalBranch(start_node=src_node, end_node=src_node, links=[], parent=None, cost=0.0)

    branch_selector = BranchSelector()
    branch_selector.put(init_branch)
    while True:
        candidate_branch = branch_selector.select()
        if candidate_branch is None:
            break
        # print map(lambda x: str(x), candidate_branch.links), candidate_branch.cost
        # print "+++++++++++++++"
        for next_branch in branch_expander.expand(candidate_branch):
            branch_selector.put(next_branch)

    if branch_selector.pri_dict.has_key(des_node.name):
        branches = branch_selector.pri_dict[des_node.name]
        if branches:
            return [[link.pair() for link in branch.links] for branch in branches]
    return None


if __name__ == "__main__":
    with open_plan('/home/lequ/wae-run/180611_0533_UTC-SLOpt.pln') as network:
        t1 = time.time()
        lsp = network.model.lsps["lsp{SZ-PE01|tunnel-te1000}"]
        paths = extract_paths(network, lsp, 'te_metric')
        for path in paths:
            print path
        t2 = time.time()
        print t2 - t1

