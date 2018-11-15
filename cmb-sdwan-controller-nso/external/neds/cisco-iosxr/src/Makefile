
JAVA_PACKAGE = com.tailf.packages.ned.iosxr
JDIR = $(shell echo $(JAVA_PACKAGE) | sed 's/\./\//g')

NS  = namespaces

JFLAGS = --java-disable-prefix \
         --exclude-enums \
         --fail-on-warnings \
         --java-package $(JAVA_PACKAGE).$(NS) \
         --emit-java

all:	filter-yang fxs tmp-yang/schema.json javac netsim nedcom_tidy

netsim:
	cd ../netsim && $(MAKE) all

# Include standard NCS examples build definitions and rules
include $(NCS_DIR)/src/ncs/build/include.ncs.mk

MAIN_YANG_MODULE = tailf-ned-cisco-ios-xr.yang
PYANG_JSON_XARGS = --json-fwhen="^/route-policy$$" --json-fwhen="^/route-policy-edit$$"

-include ../../nedcom/src/nedcom.mk

include ned-yang-filter.mk

NCSC_VER := $(shell ${NCSC} --version)
ifneq (,$(findstring 6.2,${NCSC_VER}))
SUPPRESS_WARN := --no-warning TAILF_SUGGEST_COMBO
endif

YANG = yang/tailf-ned-cisco-ios-xr.yang \
       yang/tailf-ned-cisco-ios-xr-id.yang \
       yang/tailf-ned-cisco-ios-xr-stats.yang \
       yang/tailf-ned-cisco-ios-xr-meta.yang \
       yang/tailf-ned-cisco-ios-xr-oper.yang


SRC = $(wildcard tmp-yang/*.yang)
FXS = $(SRC:tmp-yang/%.yang=ncsc-out/modules/fxs/%.fxs)

JAVA_NAMESPACES = \
	$(shell echo $(YANG:yang/%.yang=java/src/$(JDIR)/namespaces/%.java) | \
		perl -pne 's/([a-zA-Z])-([a-zA-Z])/\1\U\2/g' | \
		perl -pne 's/tailfNed//g')

javaname:
	echo $(JAVA_NAMESPACES)

$(FXS): $(YANG)

fxs:	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr.fxs \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-stats.fxs \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-id.fxs \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-meta.fxs \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-oper.fxs \
	ncsc-out/modules/fxs/tailf-ned-loginscripts.fxs \
	ncsc-out/modules/fxs/ietf-interfaces.fxs \
	ncsc-out/modules/fxs/ietf-ip.fxs

namespaces: $(JAVA_NAMESPACES)

java/src/$(JDIR)/namespaces/CiscoIosXr.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIosXrId.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-id.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIosXrStats.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-stats.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIosXrMeta.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-meta.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIosXrOper.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-oper.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/NedLoginScripts.java: \
	ncsc-out/modules/fxs/tailf-ned-loginscripts.fxs
	$(NCSC) $(JFLAGS) $@ $<

ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr.fxs: tmp-yang/tailf-ned-cisco-ios-xr.yang
	$(NCSC) --ncs-compile-module $< \
		--ncs-device-dir ncsc-out \
		--ncs-skip-statistics \
		--ncs-device-type cli-ned \
		${SUPPRESS_WARN} && \
	cp $@ ../load-dir

ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-stats.fxs: tmp-yang/tailf-ned-cisco-ios-xr-stats.yang
	$(NCSC) --ncs-compile-module $< \
	        --ncs-skip-config \
		--ncs-skip-template \
		--ncs-device-dir ncsc-out \
		--ncs-device-type cli-ned && \
	cp $@ ../load-dir

ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-id.fxs: yang/tailf-ned-cisco-ios-xr-id.yang
	$(NCSC) --ncs-compile-module $< \
		--ncs-device-dir ncsc-out \
		--ncs-skip-statistics \
		--ncs-device-type cli-ned && \
	cp $@ ../load-dir

ncsc-out/modules/fxs/ietf-interfaces.fxs:	yang/ietf-interfaces.yang
	$(NCSC) --ncs-compile-module $< \
	        --ncs-skip-config \
		--ncs-skip-template \
		--ncs-device-dir ncsc-out \
		--ncs-device-type cli-ned && \
	cp $@ ../load-dir

ncsc-out/modules/fxs/ietf-ip.fxs:	yang/ietf-ip.yang
	$(NCSC) --ncs-compile-module $< \
	        --ncs-skip-config \
		--ncs-skip-template \
		--ncs-device-dir ncsc-out \
		--ncs-device-type cli-ned && \
	cp $@ ../load-dir

ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-meta.fxs:	tmp-yang/tailf-ned-cisco-ios-xr-meta.yang
	$(NCSC) --yangpath yang -c $< -o $@ && cp $@ ../load-dir

ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-oper.fxs:	tmp-yang/tailf-ned-cisco-ios-xr-oper.yang
	$(NCSC) --yangpath yang -c $< -o $@ && cp $@ ../load-dir

ncsc-out/modules/fxs/tailf-ned-loginscripts.fxs:	tmp-yang/tailf-ned-loginscripts.yang
	$(NCSC) --yangpath yang -c $< -o $@ && cp $@ ../load-dir

javac: namespaces ../private-jar/nedcom.jar
	cd java && ant -q all

clean: nedcom_clean
	rm -rf ncsc-out/* ../load-dir/* ncsc-out/*
	rm -f ../private-jar/iosxr.jar
	rm -f java/src/$(JDIR)/$(NS)/*.java
	cd java && ant clean
	cd ../netsim && $(MAKE) clean
