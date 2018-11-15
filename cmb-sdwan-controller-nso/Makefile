all:
	$(MAKE) setup
	$(MAKE) -C cmb-sdwan-sr-te/src all
setup:
clean:
	for f in 'cmb-sdwan-sr-te/src/'; do \
		$(MAKE) -C $$f clean || exit 1; \
	done
	
package:clean all
	rm -rf ./tmp/ && mkdir ./tmp/ && cp -r ./cmb-sdwan-sr-te/ ./tmp/
	rm -rf ./tmp/cmb-sdwan-sr-te/src/java/src
	cd ./tmp/ && ls | xargs -i tar -zcvf {}.tar.gz {}
	rm -rf ./release/ && mkdir ./release/ && mv ./tmp/*.tar.gz ./release/ && rm -rf ./tmp/

