import unittest
import sys
import os
print(sys.path)
sys.path.append('../../')

loader = unittest.TestLoader()

case_dir = os.path.abspath('./script')
print case_dir
suite = loader.discover(case_dir)
runner = unittest.TextTestRunner(verbosity=2)
runner.run(suite)
