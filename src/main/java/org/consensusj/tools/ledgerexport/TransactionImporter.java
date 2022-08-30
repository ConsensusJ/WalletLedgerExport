package org.consensusj.tools.ledgerexport;

import foundation.omni.CurrencyID;
import foundation.omni.Ecosystem;
import foundation.omni.json.pojo.BitcoinTransactionInfo;
import foundation.omni.json.pojo.OmniTransactionInfo;
import foundation.omni.money.OmniCurrencyCode;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// TODO: Allow various types of configuration in its constructor (e.g. income/expense account mappings, additional hints, etc.)
// TODO: Simplify static constructors by factoring out common code
// TODO: Handle Omni Create Token and Omni MetaDex transactions (calculate other fees and funds locked in multisig)
// TODO: Figure out why BTC balance is off (low) for Sean's wallet (given other TODOs I'd expect the BTC balance to be too high)
// TODO: Get total fees of Simple Send  (calculate other fees and funds locked in multisig)
// TODO: Get total fees of Omni TEST ecosystem transactions
/**
 * Imports transactions from ConsolidatedTransaction to LedgerTransaction format
 */
public class TransactionImporter {
    private static final Logger log = LoggerFactory.getLogger(TransactionImporter.class);
    private static final String BTC_CODE = OmniCurrencyCode.BTC.toString();
    private static final String walletAccount = "Assets:Crypto:OmniCore";
    private static final String defaultIncome = "Income:Misc";
    private static final String defaultExpense = "Expense:Misc";
    private static final Map<String, String> tickerMap = Map.of("OMNI_SPT#57", "SAFEAPP");
    private final Map<Address, AddressAccount> addressAccountMap;

    public TransactionImporter() {
        this.addressAccountMap = new HashMap<>();
    }

    public TransactionImporter(List<AddressAccount> addressAccounts) {
        this.addressAccountMap = addressAccounts.stream()
                .collect(Collectors.toMap(AddressAccount::address, Function.identity()));
    }
    
    public List<LedgerTransaction> importTransactions(List<ConsolidatedTransaction> consTxs) {
        return consTxs.stream()
                .map(this::fromConsolidated)
                .toList();
    }

    public LedgerTransaction fromConsolidated(ConsolidatedTransaction cons) {
        boolean isOmni = cons.omniTx() != null;
        if (cons.txId().toString().equals("91c44eaa107acb8aac4bb283aba91a6fed6bcff91f0b41e5202f3df026dca68e"))  {
            log.warn("txid is {}", cons.txId());
        }
        if (isOmni) {
            if (cons.outputs().size() == 1) {
                if (cons.outputs().get(0).getCategory().equals("send")) {
                    log.warn("Unexpected: Our wallet sent an Omni tx with only one BitcoinTransactionInfo {}", cons.txId());
                }
                return fromReceivedOmni(cons);
            } else {
                log.warn("Omni Tx {}", cons.txId());
                return fromSentOmni(cons);
            }
        } else {
            if (cons.outputs().size() == 1) {
                return fromBitcoin(cons);
            } else {
                // Bitcoin Tx with multiple outputs -- i.e. not just dest and change -- generally a "Payment to yourself"
                return fromBitcoinSelfSend(cons);
            }
        }
    }

    public LedgerTransaction fromReceivedOmni(ConsolidatedTransaction ct) {
        if (ct.outputs().size() != 1) {
            throw new IllegalStateException("expected single Bitcoin transaction");
        }
        if (ct.omniTx() == null) {
            throw new IllegalStateException("Expected Omni transaction");
        }
        BitcoinTransactionInfo bitcoin = ct.outputs().get(0);
        OmniTransactionInfo omni = ct.omniTx();
        boolean isSend = bitcoin.getCategory().equals("send");
        boolean isOmni = (omni != null);
        String account = isSend ? defaultExpense : incomeAccount(bitcoin.getAddress());
        LocalDateTime time = timeFromEpoch(bitcoin.getTime());
        BigDecimal fee = (bitcoin.getFee() != null) ? bitcoin.getFee().toBtc() : BigDecimal.ZERO;

        Optional<OmniTransactionInfo> o = Optional.ofNullable(omni);
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
                if (isOmni) {
                    // Deduct fee from BTC assets
                    splits.add(new LedgerTransaction.Split(walletAccount, fee, OmniCurrencyCode.BTC.toString()));
                }
                splits.add(new LedgerTransaction.Split("Expense:TransactionFees",fee.negate(), BTC_CODE));
            }
        }

        List<String> comments = List.of(
                commentTxId(bitcoin.getTxId()),
                commentBtcTx(bitcoin),
                commentOmniTx(omni)
        );

        return new LedgerTransaction(bitcoin.getTxId(),
                time,
                bitcoin.getLabel(),
                comments,
                Collections.unmodifiableList(splits));
    }

    public LedgerTransaction fromSentOmni(ConsolidatedTransaction ct) {
        if (ct.omniTx().getPropertyId() != null && ct.omniTx().getPropertyId().ecosystem() == Ecosystem.TOMNI) {
            return fromOmniTestEcosystem(ct);
        }
        String account = defaultExpense;
        LocalDateTime time = timeFromInstant(ct.time());
        BigDecimal fee = ct.outputs().stream()
                .map(BitcoinTransactionInfo::getFee)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Coin.ZERO)
                .toBtc();

        List<LedgerTransaction.Split> splits = new ArrayList<>();

        // Wallet account
        BigDecimal amount = ct.omniTx().getAmount() != null ? ct.omniTx().getAmount().bigDecimalValue() : BigDecimal.ZERO;
        String currency = calcCurrency(ct.omniTx());
        splits.add(new LedgerTransaction.Split(walletAccount, amount.negate(), currency));

        // Other account
        splits.add(new LedgerTransaction.Split(account, amount, currency));

        // Fee
        if (fee.compareTo(BigDecimal.ZERO) != 0) {
            // Deduct fee from BTC assets
            splits.add(new LedgerTransaction.Split(walletAccount, fee, OmniCurrencyCode.BTC.toString()));
            // Add Fee expense
            splits.add(new LedgerTransaction.Split("Expense:TransactionFees",fee.negate(), BTC_CODE));
        }

        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(ct.txId()));
        comments.addAll(commentsBtcTxs(ct.outputs()));
        comments.add(commentOmniTx(ct.omniTx()));

        return new LedgerTransaction(ct.txId(),
                time,
                "omni send",
                comments,
                Collections.unmodifiableList(splits));
    }

    public LedgerTransaction fromOmniTestEcosystem(ConsolidatedTransaction ct) {
        LocalDateTime time = timeFromInstant(ct.time());
        BigDecimal fee = ct.outputs().stream()
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
            splits.add(new LedgerTransaction.Split("Expense:TransactionFees", fee.negate(), OmniCurrencyCode.BTC.toString()));
        }

        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(ct.txId()));
        comments.addAll(commentsBtcTxs(ct.outputs()));

        return new LedgerTransaction(ct.txId(),
                time,
                "Omni Testnet Transaction (fees only)",
                comments,
                splits);
    }

    public LedgerTransaction fromBitcoin(ConsolidatedTransaction ct) {
        if (ct.outputs().size() != 1) {
            throw new IllegalStateException("expected single Bitcoin transaction");
        }
        if (ct.omniTx() != null) {
            throw new IllegalStateException("Unexpected Omni transaction");
        }
        BitcoinTransactionInfo bitcoin = ct.outputs().get(0);
        boolean isSend = bitcoin.getCategory().equals("send");
        String account = isSend ? defaultExpense : incomeAccount(bitcoin.getAddress());
        LocalDateTime time = timeFromEpoch(bitcoin.getTime());
        BigDecimal fee = (bitcoin.getFee() != null) ? bitcoin.getFee().toBtc() : BigDecimal.ZERO;

        List<LedgerTransaction.Split> splits = new ArrayList<>();

        // Wallet account
        // Amount will already be positive for incoming and negative for outgoing
        BigDecimal amount = bitcoin.getAmount().toBtc();
        String currency = BTC_CODE;
        splits.add(new LedgerTransaction.Split(walletAccount, amount, currency));

        // Other account
        BigDecimal otherAmount = isSend
                ? amount.subtract(fee)     // amount is negative, subtracting negative fee reduces magnitude
                : amount;
        splits.add(new LedgerTransaction.Split(account, otherAmount.negate(), currency));

        // Fee
        if (isSend) {
            if (fee.compareTo(BigDecimal.ZERO) != 0) {
                splits.add(new LedgerTransaction.Split("Expense:TransactionFees",fee.negate(), OmniCurrencyCode.BTC.toString()));
            }
        }

        List<Address> addresses = ct.addresses();
        List<String> addressStrings = (addresses != null)
                                            ? addresses.stream().map(Address::toString).toList()
                                            : Collections.emptyList();

        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(bitcoin.getTxId()));
        comments.add(commentBtcTx(bitcoin));
        comments.addAll(addressStrings);

        return new LedgerTransaction(bitcoin.getTxId(),
                time,
                bitcoin.getLabel(),
                comments,
                Collections.unmodifiableList(splits));
    }

    public LedgerTransaction fromBitcoinSelfSend(ConsolidatedTransaction ct) {
        if (ct.omniTx() != null) {
            throw new IllegalStateException("Unexpected Omni transaction");
        }
        List<BitcoinTransactionInfo> bts = ct.outputs();
        LocalDateTime time = timeFromEpoch(bts.get(0).getTime());
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
            splits.add(new LedgerTransaction.Split("Expense:TransactionFees", fee.negate(), OmniCurrencyCode.BTC.toString()));
        }

        List<Address> addresses = ct.addresses();
        List<String> addressStrings = (addresses != null)
                ? addresses.stream().map(Address::toString).toList()
                : Collections.emptyList();


        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(bts.get(0).getTxId()));
        comments.addAll(commentsBtcTxs(bts));
        comments.addAll(addressStrings);

        return new LedgerTransaction(bts.get(0).getTxId(),
                time,
                "Self send (consolidating tx)",
                comments,
                splits);
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
        return String.format("omni tx type: %s(%d), send-addr: %s, ref-addr: %s",
                ot.getType(),
                ot.getTypeInt(),
                ot.getSendingAddress(),
                ot.getReferenceAddress().map(Address::toString).orElse("n/a"));
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
            return omni.getPropertyId() != null ? propertyIdToTicker(omni.getPropertyId()) : OmniCurrencyCode.BTC.toString();
        } else {
            return OmniCurrencyCode.BTC.toString();
        }
    }

    private static String propertyIdToTicker(CurrencyID id) {
        String code = OmniCurrencyCode.idToCodeString(id);
        String mapped = tickerMap.get(code);    // OMNI_SPT#xx not valid in ledger-cli so map it to something else
        return (mapped != null) ? mapped : code;
    }

    private static LocalDateTime timeFromEpoch(long epoch) {
        return timeFromInstant(Instant.ofEpochSecond(epoch));
    }

    private static LocalDateTime timeFromInstant(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
