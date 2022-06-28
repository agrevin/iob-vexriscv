include config.mk

# Rules
.PHONY: vexriscv clean qemu

CPU ?= LinuxGen
#CPU ?= GenFullNoMmuNoCache

vexriscv:
	cp $(VEX_CORE_DIR)/* submodules/VexRiscv/src/main/scala/vexriscv/demo/ && cd submodules/VexRiscv \
		&& sbt "runMain vexriscv.demo.$(CPU)" && cp VexRiscv.v $(VEXRISCV_SRC_DIR)

build-opensbi: clean-opensbi
	cp -r $(VEX_PLATAFORM_DIR)/* $(VEX_OPENSBI_DIR)/platform/ && cd $(VEX_OPENSBI_DIR) && $(MAKE) run PLATFORM=iob_soc

build-iob-linux: clean-buildroot
	cd buildroot && $(MAKE) iob_riscv32_defconfig && $(MAKE) -j3
	cp buildroot/output/images/* $(VEX_OS_DIR)

build-qemu: clean-buildroot
	mkdir LinuxOS
	cd buildroot && $(MAKE) qemu_riscv32_virt_defconfig && $(MAKE) -j2
	cp buildroot/output/images/* LinuxOS

run-qemu:
	qemu-system-riscv32 -M virt -bios LinuxOS/fw_jump.elf -kernel LinuxOS/Image -append "rootwait root=/dev/vda ro" -drive file=LinuxOS/rootfs.ext2,format=raw,id=hd0 -device virtio-blk-device,drive=hd0 -netdev user,id=net0 -device virtio-net-device,netdev=net0 -nographic

build-dts:
	dtc -O dtb -o $(VEX_OS_DIR)/iob_soc.dtb software/iob_soc.dts


#
# Clean
#
clean-opensbi:
	cd $(VEX_OPENSBI_DIR) && $(MAKE) distclean

clean-buildroot:
	cd buildroot && $(MAKE) clean && rm -rf dl output

clean-linux:
	@rm -rf ./LinuxOS

clean-all: clean-buildroot clean-linux
