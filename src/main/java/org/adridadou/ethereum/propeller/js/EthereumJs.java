package org.adridadou.ethereum.propeller.js;

import com.eclipsesource.v8.V8;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.ReplaySubject;
import org.adridadou.ethereum.propeller.EthereumBackend;
import org.adridadou.ethereum.propeller.event.BlockInfo;
import org.adridadou.ethereum.propeller.event.EthereumEventHandler;
import org.adridadou.ethereum.propeller.solidity.SolidityEvent;
import org.adridadou.ethereum.propeller.values.*;
import org.apache.commons.io.IOUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.crypto.SECP256K1;
import org.apache.tuweni.eth.Address;
import org.apache.tuweni.eth.Transaction;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.ethereum.Gas;
import org.apache.tuweni.units.ethereum.Wei;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.core.DefaultBlockParameter;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by davidroon on 20.01.17.
 * This code is released under Apache 2 license
 */
public class EthereumJs implements EthereumBackend {
    private final V8 runtime = V8.createV8Runtime();
    private final EthereumJsConfig jsConfig;
    private final ReplaySubject<Transaction> transactionPublisher = ReplaySubject.create(100);
    private final Flowable<Transaction> transactionObservable = transactionPublisher.toFlowable(BackpressureStrategy.BUFFER);
    //private final LocalExecutionService localExecutionService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Logger logger = LoggerFactory.getLogger(EthereumJs.class);

    public EthereumJs(EthereumJsConfig jsConfig) throws IOException {
        String script = IOUtils.toString(getClass().getClassLoader().getResourceAsStream("ethereum.js"), StandardCharsets.UTF_8);

        runtime.executeVoidScript(script);
        processTransactions();
        this.jsConfig = jsConfig;
    }

    private void processTransactions() {
        transactionObservable
                .doOnError(err -> logger.error(err.getMessage(), err))
                .doOnNext(next -> logger.debug("New transaction to process"))
                .subscribeOn(Schedulers.from(executor));
                //.subscribe(tx -> executor.submit(() -> process(tx)));
    }

    @Override
    public GasPrice getGasPrice() {
        return new GasPrice(EthValue.wei(1));
    }

    @Override
    public EthValue getBalance(EthAddress address) {
        return EthValue.wei(1);
        //return EthValue.wei(blockchain.getBlockchain().getRepository().getBalance(address.address));
    }

    @Override
    public boolean addressExists(EthAddress address) {
        return false;
        //return blockchain.getBlockchain().getRepository().isExist(address.address);
    }

    @Override
    public EthHash submit(TransactionRequest request, Nonce nonce) {
        Transaction tx = createTransaction(request, nonce);
        transactionPublisher.onNext(tx);
        return EthHash.of(tx.hash().toHexString());
    }

    private Transaction createTransaction(TransactionRequest request, Nonce nonce) {
        UInt256 nonceInt = UInt256.valueOf(nonce.getValue());
        Wei gasPriceWei = Wei.valueOf(request.getGasPrice().getPrice().inWei());
        Gas gasLimitWei = Gas.valueOf(request.getGasLimit().getUsage());
        Wei value = Wei.valueOf(request.getValue().inWei());
        Bytes payload = Bytes.of(request.getData().data);
        SECP256K1.KeyPair keyPair = SECP256K1.KeyPair.fromSecretKey(SECP256K1.SecretKey.fromInteger(request.getAccount().getBigIntPrivateKey()));
        if (request.getAddress().isEmpty()) {
            //the signature gets generated when the Transaction is created
            return new org.apache.tuweni.eth.Transaction(nonceInt, gasPriceWei, gasLimitWei,
                    null, value, payload, keyPair, 10);
        }
        else {
            Address address = Address.fromBytes(Bytes.of(request.getAddress().toData().data));
            return new org.apache.tuweni.eth.Transaction(nonceInt, gasPriceWei, gasLimitWei,
                    address, value, payload, keyPair, 10);
        }
    }

    @Override
    public GasUsage estimateGas(final EthAccount account, final EthAddress address, final EthValue value, final EthData data) {
        return new GasUsage(BigInteger.ONE);
        //return localExecutionService.estimateGas(account, address, value, data);
    }

    @Override
    public Nonce getNonce(EthAddress currentAddress) {
        return new Nonce(BigInteger.ONE);
        //return new Nonce(blockchain.getBlockchain().getRepository().getNonce(currentAddress.address));
    }

    @Override
    public long getCurrentBlockNumber() {
        return 1L;
        //return blockchain.getBlockchain().getBestBlock().getNumber();
    }

    @Override
    public Optional<BlockInfo> getBlock(long blockNumber) {
        return Optional.empty();
        //return Optional.ofNullable(blockchain.getBlockchain().getBlockByNumber(blockNumber)).map(this::toBlockInfo);
    }

    @Override
    public Optional<BlockInfo> getBlock(EthHash blockNumber) {
        return Optional.empty();
        //return Optional.ofNullable(blockchain.getBlockchain().getBlockByHash(blockNumber.data)).map(this::toBlockInfo);
    }

    @Override
    public SmartContractByteCode getCode(EthAddress address) {
        //return SmartContractByteCode.of(blockchain.getBlockchain().getRepository().getCode(address.address));
        return null;
    }

    @Override
    public synchronized EthData constantCall(final EthAccount account, final EthAddress address, final EthValue value, final EthData data) {
        //return localExecutionService.executeLocally(account, address, value, data);
        return EthData.empty();
    }

    @Override
    public List<EventData> logCall(DefaultBlockParameter fromBlock, DefaultBlockParameter toBlock, SolidityEvent eventDefinition, EthAddress address, String... optionalTopics) {
        ArrayList events = new ArrayList();

        for (long i = 0; i < this.getCurrentBlockNumber(); i++) {
            BlockInfo block = this.getBlock(i).get();
            block.receipts.stream()
                    .filter(params -> address.equals(params.receiveAddress))
                    .flatMap(params -> params.events.stream())
                    .filter(eventDefinition::match).forEach(eventData -> {

                // If we have indexed parameters to match check if we matched them all here
                if (optionalTopics.length > 0) {
                    int matched = 0;
                    for (int j = 0; j < optionalTopics.length; j++) {

                        // Check if null / matching, since null can be passed if we don't care about it being matched with multiple
                        // indexed parameters (this is web3j behaviour as well)
                        if (optionalTopics[j] == null || optionalTopics[j].equals(eventData.getIndexedArguments().get(j).withLeading0x())) {
                            matched++;
                        }
                    }

                    // If equals to matched the events matched everything and should be added
                    if (optionalTopics.length == matched) {
                        events.add(eventData);
                    }

                // If there are no optional parameters add to return list since eventDefinitions matched
                } else {
                    events.add(eventData);
                }
            });
        }
        return events;
    }

    @Override
    public void register(EthereumEventHandler eventHandler) {
        eventHandler.onReady();
        //blockchain.addEthereumListener(new EthJEventListener(eventHandler));
    }

    @Override
    public Optional<TransactionInfo> getTransactionInfo(EthHash hash) {
        /*
        return Optional.ofNullable(blockchain.getBlockchain().getTransactionInfo(hash.data)).map(info -> {
            EthHash blockHash = EthHash.of(info.getBlockHash());
            TransactionStatus status = info.isPending() ? TransactionStatus.Pending : blockHash.isEmpty() ? TransactionStatus.Unknown : TransactionStatus.Executed;
            return new TransactionInfo(hash, EthJEventListener.toReceipt(info.getReceipt(), blockHash), status, blockHash);
        });
         */
        return Optional.empty();
    }

    @Override
    public ChainId getChainId() {
        return ChainId.id(123456);
    }
}
