all:
	$(MAKE) setup
	$(MAKE) -C cisco-wae-opm-sr-lsp-path-computation/src all
	$(MAKE) -C cisco-wae-opm-sr-lsp-path-initialization/src all
	$(MAKE) -C cisco-wae-opm-sr-lsp-bw-optimization/src all
	$(MAKE) -C cisco-wae-custom-network-info-nimo/src all
setup:
	cp common.py cisco-wae-opm-sr-lsp-path-computation/python/
	cp common.py cisco-wae-opm-sr-lsp-path-initialization/python/
	cp common.py cisco-wae-opm-sr-lsp-bw-optimization/python/
	cp common.py cisco-wae-custom-network-info-nimo/python/
clean:
	for f in 'cisco-wae-opm-sr-lsp-path-computation/src/'; do \
		$(MAKE) -C $$f clean || exit 1; \
	done
	for f in 'cisco-wae-opm-sr-lsp-path-initialization/src/'; do \
		$(MAKE) -C $$f clean || exit 1; \
	done
	for f in 'cisco-wae-opm-sr-lsp-bw-optimization/src/'; do \
		$(MAKE) -C $$f clean || exit 1; \
	done
	for f in 'cisco-wae-custom-network-info-nimo/src/'; do \
		$(MAKE) -C $$f clean || exit 1; \
	done
	rm -rf cisco-wae-opm-sr-lsp-path-computation/python/common.py
	rm -rf cisco-wae-opm-sr-lsp-path-initialization/python/common.py
	rm -rf cisco-wae-opm-sr-lsp-bw-optimization/python/common.py
	rm -rf cisco-wae-custom-network-info-nimo/python/common.py
	rm -rf cmb-sdwan-controller.tar.gz

unittest:
	cd ./test/unittest/ && python test_runner.py
unittest-clean:
	find ./test/ -name '*.pyc' -exec rm --force {} \;
package:clean all
	rm -rf ./tmp/
	mkdir ./tmp/
	cp -r cisco-wae*/ tmp/
	find ./tmp/ -name '*.py' | xargs python -m py_compile 
	find ./tmp/ -name '*.py' -exec rm --force {} \;
	cd ./tmp/ && ls | xargs -i tar -zcvf {}.tar.gz {}
	rm -rf ./release/ && mkdir ./release/ && mv ./tmp/*.tar.gz ./release/ && rm -rf ./tmp/
	#tar -zcvf cmb-sdwan-controller.tar.gz ../cmb-sdwan-controller

