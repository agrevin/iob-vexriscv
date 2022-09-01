package vexriscv.demo

import spinal.lib.eda.bench.{AlteraStdTargets, Bench, Rtl, XilinxStdTargets}
import spinal.lib.eda.icestorm.IcestormStdTargets
import spinal.lib.master

import vexriscv.plugin._
import vexriscv.ip.{DataCacheConfig, InstructionCacheConfig}
import vexriscv.ip.fpu.{FpuCore, FpuParameter}
import vexriscv.{plugin, VexRiscv, VexRiscvConfig}
import spinal.core._

object LinuxGen {
  def configFull(litex : Boolean, withMmu : Boolean, withSmp : Boolean = false) = {
    val config = VexRiscvConfig(
      plugins = List(
        new IBusCachedPlugin(
          resetVector = 0x80000000l,
          prediction = NONE,
          historyRamSizeLog2 = 10,
          compressedGen = true,
          injectorStage = true,
          config = InstructionCacheConfig(
            cacheSize = 4096,
            bytePerLine = 4,
            wayCount = 1,
            addressWidth = 32,
            cpuDataWidth = 32,
            memDataWidth = 32,
            catchIllegalAccess = true,
            catchAccessFault = true,
            asyncTagMemory = false,
            twoCycleRam = true,
            twoCycleCache = true
          ),
          memoryTranslatorPortConfig = MmuPortConfig(
            portTlbSize = 4
          )
        ),
        new DBusCachedPlugin(
          //dBusCmdMasterPipe = true,
          //dBusCmdSlavePipe = true,
          //dBusRspSlavePipe = true,
          config = new DataCacheConfig(
            cacheSize         = 4096,
            bytePerLine       = 4,
            wayCount          = 1,
            addressWidth      = 32,
            cpuDataWidth      = 32,
            memDataWidth      = 32,
            catchAccessError  = true,
            catchIllegal      = true,
            catchUnaligned    = true,
            withExclusive = withSmp,
            withInvalidate = withSmp,
            withLrSc = true,
            withAmo = true
          ),
          memoryTranslatorPortConfig = MmuPortConfig(
            portTlbSize = 6
          )
        ),
        new DecoderSimplePlugin(
          catchIllegalInstruction = true
        ),
        new RegFilePlugin(
          regFileReadyKind = plugin.SYNC,
          zeroBoot = true
        ),
        new IntAluPlugin,
        new SrcPlugin(
          separatedAddSub = true
        ),
        new FullBarrelShifterPlugin(earlyInjection = false),
        new HazardSimplePlugin(
          bypassExecute           = true,
          bypassMemory            = true,
          bypassWriteBack         = true,
          bypassWriteBackBuffer   = true,
          pessimisticUseSrc       = false,
          pessimisticWriteRegFile = false,
          pessimisticAddressMatch = false
        ),
        new MulPlugin,
        new MulDivIterativePlugin(
          genMul = false,
          genDiv = true,
          mulUnrollFactor = 32,
          divUnrollFactor = 4
        ),
        new CsrPlugin(CsrPluginConfig.linuxFull(0x80000020l).copy(misaExtensionsInit = 0x0141105, ebreakGen = true)),
        //new DebugPlugin(ClockDomain.current.clone(reset = Bool().setName("debugReset"))),
        new BranchPlugin(
          earlyBranch = false,
          catchAddressMisaligned = true,
          fenceiGenAsAJump = false
        ),
        new MmuPlugin(ioRange = (x => x(31 downto 30) === 0x1)),
        new FpuPlugin(externalFpu = false, simHalt = false, p = FpuParameter(withDouble = false)),
        new YamlPlugin("cpu0.yaml")
      )
    )
    config
  }

  def main(args: Array[String]) {
    SpinalConfig(mergeAsyncProcess = false, anonymSignalPrefix = "_zz").generateVerilog {
      val toplevel = new VexRiscv(configFull(
        litex = !args.contains("-r"),
        withMmu = true
      ))
      toplevel
    }
  }
}

object LinuxSyntesisBench extends App{
  val withoutMmu = new Rtl {
    override def getName(): String = "VexRiscv Without Mmu"
    override def getRtlPath(): String = "VexRiscvWithoutMmu.v"
    SpinalConfig(inlineRom=true).generateVerilog(new VexRiscv(LinuxGen.configFull(litex = false, withMmu = false)).setDefinitionName(getRtlPath().split("\\.").head))
  }

  val withMmu = new Rtl {
    override def getName(): String = "VexRiscv With Mmu"
    override def getRtlPath(): String = "VexRiscvWithMmu.v"
    SpinalConfig(inlineRom=true).generateVerilog(new VexRiscv(LinuxGen.configFull(litex = false, withMmu = true)).setDefinitionName(getRtlPath().split("\\.").head))
  }

  val rtls = List(withoutMmu,withMmu)

  val targets = XilinxStdTargets(
    vivadoArtix7Path = "/media/miaou/HD/linux/Xilinx/Vivado/2018.3/bin"
  ) ++ AlteraStdTargets(
    quartusCycloneIVPath = "/media/miaou/HD/linux/intelFPGA_lite/18.1/quartus/bin",
    quartusCycloneVPath  = "/media/miaou/HD/linux/intelFPGA_lite/18.1/quartus/bin"
  )

  Bench(rtls, targets, "/media/miaou/HD/linux/tmp")
}

object LinuxSim extends App{
  import spinal.core.sim._

  SimConfig.allOptimisation.compile(new VexRiscv(LinuxGen.configFull(litex = false, withMmu = true))).doSim{dut =>

    var cycleCounter = 0l
    var lastTime = System.nanoTime()

    var iBus : IBusSimpleBus = null
    var dBus : DBusSimpleBus = null
    dut.plugins.foreach{
      case p : IBusSimplePlugin =>
        iBus = p.iBus
      case p : DBusSimplePlugin =>
        dBus = p.dBus
      case _ =>
    }

    dut.clockDomain.resetSim #= false
    dut.clockDomain.clockSim #= false
    sleep(1)
    dut.clockDomain.resetSim #= true
    sleep(1)

    def f(): Unit ={
      cycleCounter += 1

      if((cycleCounter & 8191) == 0){
        val currentTime = System.nanoTime()
        val deltaTime = (currentTime - lastTime)*1e-9
        if(deltaTime > 2.0) {
          println(f"[Info] Simulation speed : ${cycleCounter / deltaTime * 1e-3}%4.0f kcycles/s")
          lastTime = currentTime
          cycleCounter = 0
        }
      }
      dut.clockDomain.clockSim #= false
      iBus.cmd.ready #= ! iBus.cmd.ready.toBoolean
      dBus.cmd.ready #= ! dBus.cmd.ready.toBoolean
      delayed(1)(f2)
    }
    def f2(): Unit ={
      dut.clockDomain.clockSim #= true
      delayed(1)(f)
    }

    delayed(1)(f)

    sleep(100000000)
  }
}
