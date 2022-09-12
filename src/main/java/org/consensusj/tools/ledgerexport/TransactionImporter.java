/*
 * Copyright 2022 M. Sean Gilligan.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.consensusj.tools.ledgerexport;

import foundation.omni.CurrencyID;
import foundation.omni.Ecosystem;
import foundation.omni.json.pojo.OmniTransactionInfo;
import foundation.omni.money.OmniCurrencyCode;
import foundation.omni.net.OmniNetworkParameters;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.consensusj.bitcoin.json.pojo.BitcoinTransactionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;


// TODO: Allow various types of configuration in its constructor (e.g. income/expense account mappings, additional hints, etc.)
// TODO: Simplify static constructors by factoring out common code
/**
 * Imports transactions from TransactionData to LedgerTransaction format
 */
public class TransactionImporter {
    private static final Logger log = LoggerFactory.getLogger(TransactionImporter.class);
    private static final String BTC_CODE = OmniCurrencyCode.BTC.toString();
    private static final String walletAccount = "Assets:Crypto:OmniCore";
    private static final String defaultIncome = "Income:Misc";
    private static final String defaultExpense = "Expense:Misc";
    private static final Map<String, String> tickerMap = Map.of("OMNI_SPT#57", "SAFEAPP");
    private final NetworkParameters netParams;
    private final Address exodusAddr;

    private final Map<Address, AddressAccount> addressAccountMap;

    /**
     * Construct with empty account mapping list
     */
    public TransactionImporter(NetworkParameters netParams) {
        this(netParams, List.of());
    }

    /**
     * Construct with account mapping list
     * @param netParams bitcoinj network params
     * @param addressAccounts A list of addresses to map to Ledger income accounts
     */
    public TransactionImporter(NetworkParameters netParams, List<AddressAccount> addressAccounts) {
        this.netParams = netParams;
        this.exodusAddr = OmniNetworkParameters.fromBitcoinParms(netParams).getExodusAddress();
        this.addressAccountMap = addressAccounts.stream()
                .collect(Collectors.toMap(AddressAccount::address, Function.identity()));
    }

    /**
     * Import consolidated transactions to Ledger objects
     * @param consTxs  list of consolidated transactions to import
     * @return list of Ledger transactions
     */
    public List<LedgerTransaction> importTransactions(List<TransactionData> consTxs) {
        return consTxs.stream()
                .map(this::fromTransactionData)
                .toList();
    }
    
    private LedgerTransaction fromTransactionData(TransactionData data) {
        if (data instanceof OmniTransactionData omniData && omniData.isOmni()) {
            if (omniData.transactionInfos().size() == 1) {
                if (omniData.transactionInfos().get(0).getCategory().equals("send")) {
                    // THIS SHOULD NEVER HAPPEN ON AN OMNI SEND
                    log.warn("Unexpected: Our wallet sent an Omni tx with only one BitcoinTransactionInfo {}", data.txId());
                }
                return fromReceivedOmni(omniData);
            } else {
                log.debug("Omni Tx {}", omniData.txId());
                return fromSentOmni(omniData);
            }
        } else if (data instanceof BitcoinTransactionData bitcoinData) {
            if (bitcoinData.transactionInfos().size() == 1) {
                return fromBitcoin(bitcoinData);
            } else {
                return fromBitcoinSelfSend(bitcoinData);
            }
        } else if (data instanceof OmniMatchData omniMatchData) {
            return fromOmniMatchData(omniMatchData);
        } else {
            throw new IllegalStateException();
        }
    }

    private LedgerTransaction fromOmniMatchData(OmniMatchData omniMatchData) {
        List<LedgerTransaction.Split> splits = new ArrayList<>();

        // Wallet account
        BigDecimal amountSold = omniMatchData.match().getAmountSold().bigDecimalValue();
        String currencySold = propertyIdToTicker(omniMatchData.trade().getPropertyIdForSale());
        splits.add(new LedgerTransaction.Split(walletAccount, amountSold.negate(), currencySold));

        // Other account

        BigDecimal amountPurchased = omniMatchData.match().getAmountReceived().bigDecimalValue();
        String currencyPurchased = propertyIdToTicker(omniMatchData.trade().getPropertyIdDesired());
        splits.add(new LedgerTransaction.Split(walletAccount, amountPurchased, currencyPurchased));


        List<String> comments = List.of(
                commentTxId(omniMatchData.txId()),
                commentOmniDexTrade(omniMatchData)
        );

        return new LedgerTransaction(omniMatchData.txId(),
                omniMatchData.time(),
                "Omni Dex Matching Transaction",
                comments,
                Collections.unmodifiableList(splits));
    }

    private LedgerTransaction fromReceivedOmni(OmniTransactionData otd) {
        if (otd.transactionInfos().size() != 1) {
            throw new IllegalStateException("expected single Bitcoin transaction");
        }
        if (otd.omniTransactionInfo() == null) {
            throw new IllegalStateException("Expected Omni transaction");
        }
        BitcoinTransactionInfo bitcoin = otd.transactionInfos().get(0);
        OmniTransactionInfo omni = otd.omniTransactionInfo();
        if (omni.getTypeInt() != 0) {
            log.warn("Expected to be receiving Omni and tx type is not SIMPLE_SEND");
        }
        boolean isSend = bitcoin.getCategory().equals("send");
        String account = isSend ? defaultExpense : incomeAccount(bitcoin.getAddress());
        BigDecimal fee = (bitcoin.getFee() != null) ? bitcoin.getFee().toBtc() : BigDecimal.ZERO;

        List<LedgerTransaction.Split> splits = new ArrayList<>();

        // Wallet account
        BigDecimal amount = calcAmount(bitcoin, omni);
        String currency = calcCurrency(omni);
        BigDecimal accountAmount = isSend ? amount.negate() : amount;
        splits.add(new LedgerTransaction.Split(walletAccount, accountAmount, currency));

        // Add BTC dust to wallet account and also count it as "income"
        if (!isSend) {
            BigDecimal dustAmount = bitcoin.getAmount().toBtc();
            splits.add(new LedgerTransaction.Split(walletAccount, dustAmount, BTC_CODE));
            splits.add(new LedgerTransaction.Split("Income:OmniDust", dustAmount.negate(), BTC_CODE));
        }

        // Other account

        BigDecimal otherAmount = isSend ? amount : amount.negate();
        splits.add(new LedgerTransaction.Split(account, otherAmount, currency));

        // Fee
        if (isSend) {
            if (fee.compareTo(BigDecimal.ZERO) != 0) {
                // Deduct fee from BTC assets
                splits.add(new LedgerTransaction.Split(walletAccount, fee, BTC_CODE));
                splits.add(new LedgerTransaction.Split("Expense:TransactionFees",fee.negate(), BTC_CODE));
            }
        }

        List<String> comments = List.of(
                commentTxId(bitcoin.getTxId()),
                commentBtcTx(bitcoin),
                commentOmniTx(omni)
        );

        return new LedgerTransaction(bitcoin.getTxId(),
                bitcoin.getTime(),
                bitcoin.getLabel(),
                comments,
                Collections.unmodifiableList(splits));
    }

    private LedgerTransaction fromSentOmni(OmniTransactionData otd) {
        OmniTransactionInfo omniTx = otd.omniTransactionInfo();
        if (!omniTx.isValid() || isTestEcosystem(omniTx) ) {
            return fromOmniTestEcosystem(otd);
        }

        List<LedgerTransaction.Split> splits = new ArrayList<>();

        BigDecimal amount = omniTx.getAmount() != null ? omniTx.getAmount().bigDecimalValue() : BigDecimal.ZERO;
        String currency = calcCurrency(omniTx);

        log.debug("Omni Transaction Type: {}", omniTx.getTypeInt());
        omniTx.transactionType().ifPresentOrElse(type -> {
            switch (type) {
                case SIMPLE_SEND -> {
                    // Wallet account
                    splits.add(new LedgerTransaction.Split(walletAccount, amount.negate(), currency));
                    // Other account
                    splits.add(new LedgerTransaction.Split(defaultExpense, amount, currency));
                }
                case METADEX_TRADE -> log.warn("Metadex Trade");
                case CREATE_PROPERTY_FIXED -> {
                    // Add new tokens to Wallet account
                    splits.add(new LedgerTransaction.Split(walletAccount, amount, currency));
                    splits.add(new LedgerTransaction.Split("Income:TokenCreation", amount.negate(), currency));
                }
                default -> log.warn("Unsupported Transaction Type: {}({})", omniTx.getType(), omniTx.getTypeInt());
            }
        },
            () -> log.warn("Unknown Transaction Type: {}({})", omniTx.getType(), omniTx.getTypeInt())
        );

        List<LedgerTransaction.Split> feeSplits = omniFees(otd);

        splits.addAll(feeSplits);

        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(otd.txId()));
        comments.addAll(commentsBtcTxs(otd.transactionInfos()));
        comments.add(commentOmniTx(omniTx));

        return new LedgerTransaction(otd.txId(),
                otd.time(),
                "omni send",
                comments,
                Collections.unmodifiableList(splits));
    }

    private LedgerTransaction fromOmniTestEcosystem(OmniTransactionData otd) {
        List<LedgerTransaction.Split> splits = omniFees(otd);

        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(otd.txId()));
        comments.addAll(commentsBtcTxs(otd.transactionInfos()));

        return new LedgerTransaction(otd.txId(),
                otd.time(),
                "Invalid or Test Ecosystem Omni Transaction (fees only)",
                comments,
                splits);
    }

    private List<LedgerTransaction.Split> omniFees(OmniTransactionData otd) {
        var omniTx = otd.omniTransactionInfo();

        // Miner fee
        var minerFee = otd.transactionInfos().stream()
                .map(BitcoinTransactionInfo::getFee)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Coin.ZERO);
;
        // Exodus Fee
        var exodusFee = otd.transactionInfos()
                .stream()
                .filter(bti -> exodusAddr.equals(bti.getAddress()))
                .findFirst()
                .map(BitcoinTransactionInfo::getAmount)
                .orElse(Coin.ZERO);

        // Reference Address Dust/Fee
        var referenceFee = (omniTx.getReferenceAddress() != null)
                ? otd.transactionInfos()
                    .stream()
                    .filter(bti -> omniTx.getReferenceAddress().equals(bti.getAddress()))
                    .findFirst()
                    .map(BitcoinTransactionInfo::getAmount)
                    .orElse(Coin.ZERO)
                : Coin.ZERO;

        // Class B Multi-sig Dust/Fee
        // TODO: Make this more robust (don't assume a null address is an Omni Class B multi-sig output)
        var multiSigFee = otd.transactionInfos()
                .stream()
                .filter(bti -> bti.getAddress() == null)
                .map(BitcoinTransactionInfo::getAmount)
                .reduce(Coin.ZERO, Coin::add);

        var totalFee = Stream.of(minerFee, exodusFee, referenceFee, multiSigFee)
                                        .reduce(Coin.ZERO, Coin::add);

        List<LedgerTransaction.Split> splits = new ArrayList<>();
        if (totalFee.signum() != 0) {
            splits.add(new LedgerTransaction.Split(walletAccount, totalFee.toBtc(), BTC_CODE));
        }
        if (minerFee.signum() != 0) {
            splits.add(new LedgerTransaction.Split("Expense:TransactionFees", minerFee.negate().toBtc(), BTC_CODE));
        }
        if (exodusFee.signum() != 0) {
            splits.add(new LedgerTransaction.Split("Expense:ExodusFees", exodusFee.negate().toBtc(), BTC_CODE));
        }
        if (referenceFee.signum() != 0) {
            splits.add(new LedgerTransaction.Split("Expense:ReferenceFees", referenceFee.negate().toBtc(), BTC_CODE));
        }
        if (multiSigFee.signum() != 0) {
            splits.add(new LedgerTransaction.Split("Expense:MultiSigFees", multiSigFee.negate().toBtc(), BTC_CODE));
        }
        return Collections.unmodifiableList(splits);
    }

    private LedgerTransaction fromBitcoin(BitcoinTransactionData btd) {
        if (btd.transactionInfos().size() != 1) {
            throw new IllegalStateException("expected single Bitcoin transaction");
        }
        BitcoinTransactionInfo bitcoin = btd.transactionInfos().get(0);
        if (bitcoin.isAbandoned()) {
            log.warn("abandoned transaction: {}", bitcoin.getTxId());
        }
        if (bitcoin.getWalletConflicts().size() > 0) {
            log.warn("abandoned transaction: {}", bitcoin.getTxId());
        }
        boolean isSend = bitcoin.getCategory().equals("send");
        String account = isSend ? defaultExpense : incomeAccount(bitcoin.getAddress());
        BigDecimal fee = (bitcoin.getFee() != null) ? bitcoin.getFee().toBtc() : BigDecimal.ZERO;

        List<LedgerTransaction.Split> splits = new ArrayList<>();

        // Wallet account
        // Amount will already be positive for incoming and negative for outgoing
        BigDecimal amount = bitcoin.getAmount().toBtc();
        String currency = BTC_CODE;
        splits.add(new LedgerTransaction.Split(walletAccount, isSend ? amount.add(fee) : amount, currency));

        // Other account
        BigDecimal otherAmount = isSend
                ? amount // .subtract(fee)     // amount is negative, subtracting negative fee reduces magnitude
                : amount;
        splits.add(new LedgerTransaction.Split(account, otherAmount.negate(), currency));

        // Fee
        if (isSend) {
            if (fee.compareTo(BigDecimal.ZERO) != 0) {
                splits.add(new LedgerTransaction.Split("Expense:TransactionFees",fee.negate(), BTC_CODE));
            }
        }

        List<Address> addresses = btd.addresses();
        List<String> addressStrings = (addresses != null)
                ? addresses.stream().map(Address::toString).toList()
                : Collections.emptyList();

        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(bitcoin.getTxId()));
        comments.add(commentBtcTx(bitcoin));
        comments.addAll(addressStrings);

        return new LedgerTransaction(bitcoin.getTxId(),
                bitcoin.getTime(),
                bitcoin.getLabel(),
                comments,
                Collections.unmodifiableList(splits));
    }

    private LedgerTransaction fromBitcoinSelfSend(BitcoinTransactionData btd) {
        List<BitcoinTransactionInfo> bts = btd.transactionInfos();
        BigDecimal fee = bts.stream()
                .map(BitcoinTransactionInfo::getFee)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Coin.ZERO)
                .toBtc();

        List<LedgerTransaction.Split> splits = new ArrayList<>();

        // Wallet account
        splits.add(new LedgerTransaction.Split(walletAccount, fee, BTC_CODE));

        // Fee
        if (fee.compareTo(BigDecimal.ZERO) != 0) {
            splits.add(new LedgerTransaction.Split("Expense:TransactionFees", fee.negate(), BTC_CODE));
        }

        List<Address> addresses = btd.addresses();
        List<String> addressStrings = (addresses != null)
                ? addresses.stream().map(Address::toString).toList()
                : Collections.emptyList();


        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(bts.get(0).getTxId()));
        comments.addAll(commentsBtcTxs(bts));
        comments.addAll(addressStrings);

        return new LedgerTransaction(bts.get(0).getTxId(),
                bts.get(0).getTime(),
                "Self send (consolidating tx)",
                comments,
                splits);
    }

    private boolean isTestEcosystem(OmniTransactionInfo tx) {
        return tx.transactionType().map(type -> switch(type) {
            case METADEX_TRADE ->  tx.getPropertyIdDesired().ecosystem() == Ecosystem.TOMNI;
            default -> tx.getPropertyId() != null && tx.getPropertyId().ecosystem() == Ecosystem.TOMNI;
        }).orElse(false);
    }

    private String incomeAccount(Address address) {
        AddressAccount a = this.addressAccountMap.get(address);
        return a != null ? a.account() : defaultIncome;
    }

    private static String commentTxId(Sha256Hash txId) {
        return txId.toString();
    }

    private static List<String> commentsBtcTxs(List<BitcoinTransactionInfo> bts) {
        return bts.stream()
                .map(TransactionImporter::commentBtcTx)
                .toList();
    }

    private static String commentBtcTx(BitcoinTransactionInfo bt) {
        return String.format("addr: %s (%s%s) %s vout: %d (%s)",
                bt.getAddress(),
                bt.getLabel(),
                bt.getComment().map(s -> " : " + s).orElse(""),
                bt.getAmount().toPlainString(),
                bt.getVout(),
                bt.getCategory());
    }

    private static String commentOmniTx(OmniTransactionInfo ot) {
        Address refAddress = ot.getReferenceAddress();
        String refAddrString = (refAddress != null) ? refAddress.toString() : "n/a";
        return String.format("omni tx type: %s(%d), send-addr: %s, ref-addr: %s",
                ot.getType(),
                ot.getTypeInt(),
                ot.getSendingAddress(),
                refAddrString);
    }

    private static String commentOmniDexTrade(OmniMatchData ot) {
        return String.format("omni dex trade match: matching-addr: %s",
                ot.match().getAddress());
    }

    private static BigDecimal calcAmount(BitcoinTransactionInfo bitcoin, OmniTransactionInfo omni) {
        if (omni != null) {
            return omni.getAmount() != null ? omni.getAmount().bigDecimalValue() : BigDecimal.ZERO;
        } else {
            return bitcoin.getAmount().toBtc();
        }
    }

    private static String calcCurrency(OmniTransactionInfo omni) {
        if (omni != null) {
            return omni.getPropertyId() != null ? propertyIdToTicker(omni.getPropertyId()) : BTC_CODE;
        } else {
            return BTC_CODE;
        }
    }

    private static String propertyIdToTicker(CurrencyID id) {
        String code = OmniCurrencyCode.idToCodeString(id);
        String mapped = tickerMap.get(code);    // OMNI_SPT#xx not valid in ledger-cli so map it to something else
        return (mapped != null) ? mapped : code;
    }
}
