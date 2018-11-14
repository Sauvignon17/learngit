import unittest
import os
from mock import patch

from com.cisco.wae.opm.network import open_plan
from common import LspPath, get_opm_obj


class TestLspPathClass(unittest.TestCase):

    def setUp(self):
        self.test_name = self.id().split(".")[-1]

    def tearDown(self):
        pass

    def test__populate(self):
        with open_plan('./data/LSP-optimized-before.pln') as net:
            src = get_opm_obj(net, "SH-PE01")
            des = get_opm_obj(net, "SZ-PE01")
            self.path = LspPath(net=net, source=src, destination=des)
            self.assertEqual(self.path.segment_list_hops_ip, ['192.168.100.1'])
