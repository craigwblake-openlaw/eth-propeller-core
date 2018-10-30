package org.adridadou.ethereum.propeller

import java.io.File
import java.util.concurrent.CompletableFuture

import org.adridadou.ethereum.propeller.backend.{EthereumTest, TestConfig}
import org.adridadou.ethereum.propeller.exception.EthereumApiException
import org.adridadou.ethereum.propeller.keystore.AccountProvider
import org.adridadou.ethereum.propeller.values.EthValue.ether
import org.adridadou.ethereum.propeller.values._
import org.scalatest.check.Checkers
import org.scalatest.{FlatSpec, Matchers}

import scala.compat.java8.OptionConverters._

/**
  * Created by davidroon on 11.04.17.
  * This code is released under Apache 2 license
  */
class EventsTest extends FlatSpec with Matchers with Checkers {

  private val mainAccount = AccountProvider.fromSeed("hello")
  private val ethereum = CoreEthereumFacadeProvider.create(new EthereumTest(TestConfig.builder.balance(mainAccount, ether(1000)).build), EthereumConfig.builder().build())
  private val contractSource = SoliditySource.from(new File("src/test/resources/contractEvents.sol"))

  "Events" should "be observable from the ethereum network" in {
    val address = publishAndMapContract(ethereum)
    (for (compiledContract <- ethereum.compile(contractSource).findContract("contractEvents").asScala;
          solidityEvent <- ethereum.findEventDefinition(compiledContract, "MyEvent", classOf[MyEvent]).asScala) yield {
      val myContract = ethereum.createContractProxy(compiledContract, address, mainAccount, classOf[ContractEvents])
      val observeEventWithInfo = ethereum.observeEventsWithInfo(solidityEvent, address)

      myContract.createEvent("my event is here and it is much longer than anticipated")
      val result = observeEventWithInfo.first().toBlocking.first
      result.getTransactionHash shouldBe EthHash.of("5d75e674123f0078cc6977eeb2e6c3d7a7c0cec175a1bda75401bdbaf0151989")
      result.getResult.value shouldBe "my event is here and it is much longer than anticipated"

      val events = ethereum.getEventsAtBlock(ethereum.getTransactionInfo(result.getTransactionHash).get().getBlockHash, solidityEvent, address)
      println(events)

    }).getOrElse(() => throw new EthereumApiException("something went wrong!"))
  }

  private def publishAndMapContract(ethereum: EthereumFacade) = {
    val compiledContract = ethereum.compile(contractSource).findContract("contractEvents").get
    val futureAddress = ethereum.publishContract(compiledContract, mainAccount)
    futureAddress.get
  }
}

trait ContractEvents {
  def createEvent(value: String): CompletableFuture[Void]
}

case class MyEvent(from: EthAddress, to: EthAddress, value: String, ethData: EthData, signature: EthSignature)
