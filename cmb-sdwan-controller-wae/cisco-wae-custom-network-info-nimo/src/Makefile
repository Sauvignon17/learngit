NCSC      = $(WAE_ROOT)/lib/ext/nso/bin/ncsc
YANGPATH  = $(WAE_ROOT)/packages/cisco-wae/src/yang:$(WAE_ROOT)/packages/cisco-wae-nimo/src/yang

SRC       = $(wildcard yang/*.yang)
FXS       = $(SRC:yang/%.yang=../load-dir/%.fxs)

all: fxs

fxs: mkld $(FXS)

mkld:
	mkdir -p ../python/_namespaces
	mkdir -p ../load-dir

../load-dir/%.fxs:  yang/%.yang
	$(NCSC) --yangpath $(YANGPATH) -c -o $@ $<
	$(NCSC) --emit-python ../python/_namespaces/$(notdir $(basename $<)).py $@

clean:
	rm -rf  ../load-dir/* ../python/_namespaces/*.py
	rm -rf  ./src/*/*.pyc
