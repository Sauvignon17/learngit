all: setup fxs
# Include standard NCS examples build definitions and rules
include $(NCS_DIR)/src/ncs/build/include.ncs.mk

YANGPATH  = $(WAE_ROOT)/packages/cisco-wae/src/yang
SRC = $(wildcard yang/*.yang)
FXS = $(SRC:yang/%.yang=../load-dir/%.fxs)

setup:
	mkdir -p ../python/_namespaces
	mkdir -p ../load-dir/

fxs: $(FXS)

../load-dir/%.fxs: yang/%.yang
	$(NCSC) --yangpath $(YANGPATH) -c -o $@ $<
	$(NCSC) --emit-python ../python/_namespaces/$(notdir $(basename $<)).py $@

clean:
	rm -rf  ../load-dir/* ../python/_namespaces/*.py
	rm -rf  ./src/*/*.pyc
