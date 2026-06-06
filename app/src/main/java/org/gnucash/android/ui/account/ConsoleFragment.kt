package org.gnucash.android.ui.account

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.gnucash.android.R
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.model.*
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

class ConsoleFragment : Fragment() {

    private lateinit var consoleOutput: RecyclerView
    private lateinit var suggestionRecycler: RecyclerView
    private lateinit var commandInput: EditText
    private lateinit var btnRun: Button

    private val logList = mutableListOf<LogEntry>()
    private lateinit var logAdapter: LogAdapter

    private val suggestionList = mutableListOf<String>()
    private lateinit var suggestionAdapter: SuggestionAdapter

    private val accountsDbAdapter = AccountsDbAdapter.instance
    private val transactionsDbAdapter = TransactionsDbAdapter.instance

    // Command history for swipe gestures
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    data class LogEntry(val text: String, val type: LogType)
    enum class LogType { INPUT, OUTPUT, SUCCESS, ERROR }

    data class ParsedSplit(val account: Account, val amount: BigDecimal, val type: TransactionType)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_console, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        consoleOutput = view.findViewById(R.id.console_output)
        suggestionRecycler = view.findViewById(R.id.suggestion_recycler)
        commandInput = view.findViewById(R.id.command_input)
        btnRun = view.findViewById(R.id.btn_run)

        // Initialize output list
        logAdapter = LogAdapter(logList)
        consoleOutput.layoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
        }
        consoleOutput.adapter = logAdapter

        // Initialize suggestion list
        suggestionAdapter = SuggestionAdapter(suggestionList) { suggestion ->
            completeSuggestion(suggestion)
        }
        suggestionRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        suggestionRecycler.adapter = suggestionAdapter

        // Welcome log
        printOutput("GnuCash Pocket Terminal CLI initialized.")
        printOutput("Type 'help' to view available commands.")

        // Listen for enter key on keyboard
        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                runCommand()
                true
            } else {
                false
            }
        }

        // Listen for run button click
        btnRun.setOnClickListener {
            runCommand()
        }

        // Autocomplete text watcher
        commandInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSuggestions(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Swipe history navigation on command field
        commandInput.setOnTouchListener(object : View.OnTouchListener {
            private var yStart = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        yStart = event.y
                    }
                    MotionEvent.ACTION_UP -> {
                        val yEnd = event.y
                        val diffY = yEnd - yStart
                        if (Math.abs(diffY) > 80) {
                            if (diffY < 0) {
                                // Swipe Up -> previous command in history
                                if (commandHistory.isNotEmpty()) {
                                    historyIndex = (historyIndex + 1).coerceAtMost(commandHistory.size - 1)
                                    val cmd = commandHistory[commandHistory.size - 1 - historyIndex]
                                    commandInput.setText(cmd)
                                    commandInput.setSelection(cmd.length)
                                }
                            } else {
                                // Swipe Down -> next command in history
                                if (historyIndex > 0) {
                                    historyIndex--
                                    val cmd = commandHistory[commandHistory.size - 1 - historyIndex]
                                    commandInput.setText(cmd)
                                    commandInput.setSelection(cmd.length)
                                } else if (historyIndex == 0) {
                                    historyIndex = -1
                                    commandInput.setText("")
                                }
                            }
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    private fun printOutput(text: String, type: LogType = LogType.OUTPUT) {
        logList.add(LogEntry(text, type))
        logAdapter.notifyItemInserted(logList.size - 1)
        consoleOutput.scrollToPosition(logList.size - 1)
    }

    private fun runCommand() {
        val rawCommand = commandInput.text.toString().trim()
        if (rawCommand.isEmpty()) return

        // Save in history
        if (commandHistory.isEmpty() || commandHistory.last() != rawCommand) {
            commandHistory.add(rawCommand)
        }
        historyIndex = -1

        printOutput("> $rawCommand", LogType.INPUT)
        commandInput.setText("")

        try {
            val tokens = tokenize(rawCommand)
            if (tokens.isEmpty()) return

            val cmd = tokens[0].lowercase()
            when (cmd) {
                "help" -> executeHelp()
                "clear" -> executeClear()
                "ls", "accounts" -> executeLs()
                "balance" -> executeBalance(tokens)
                "add" -> executeAdd(tokens)
                "split" -> executeSplit(tokens)
                else -> printOutput("Unknown command: '$cmd'. Type 'help' for instructions.", LogType.ERROR)
            }
        } catch (e: Exception) {
            printOutput("Error: ${e.message}", LogType.ERROR)
        }
    }

    private fun executeHelp() {
        val helpText = """
            Available commands:
              help - Shows this help menu.
              clear - Clears the console screen.
              ls / accounts - Lists all accounts and their balances.
              balance <account_name> - Displays the balance of a specific account.
              add <description> <amount> <debit_account> <credit_account> - Creates a double-entry transaction.
              split <description> <account_1> <amount_1> <type_1> <account_2> <amount_2> <type_2> ... - Creates a transaction with multiple splits. (type can be 'd' or 'c').
              
            * Wrap description or account names in double quotes if they contain spaces.
            * Swipe UP/DOWN on the input box to cycle command history.
        """.trimIndent()
        printOutput(helpText)
    }

    private fun executeClear() {
        logList.clear()
        logAdapter.notifyDataSetChanged()
    }

    private fun executeLs() {
        val accounts = accountsDbAdapter.allRecords
        if (accounts.isEmpty()) {
            printOutput("No accounts found.")
            return
        }
        val sb = StringBuilder("Accounts and Balances:\n")
        for (acc in accounts) {
            if (acc.isPlaceholder) continue
            val fullName = acc.fullName ?: acc.name
            val balance = accountsDbAdapter.getAccountBalance(acc.uid)
            sb.append("  $fullName: ${balance.formattedString()}\n")
        }
        printOutput(sb.toString().trimEnd())
    }

    private fun executeBalance(tokens: List<String>) {
        if (tokens.size < 2) {
            printOutput("Usage: balance <account_name>", LogType.ERROR)
            return
        }
        val accountName = tokens[1]
        val account = resolveAccount(accountName)
            ?: throw Exception("Account not found: '$accountName'")
        
        val balance = accountsDbAdapter.getAccountBalance(account.uid)
        printOutput("${account.fullName ?: account.name} Balance: ${balance.formattedString()}")
    }

    private fun executeAdd(tokens: List<String>) {
        if (tokens.size < 5) {
            printOutput("Usage: add <description> <amount> <debit_account> <credit_account>", LogType.ERROR)
            return
        }

        val description = tokens[1]
        val amountStr = tokens[2]
        val debitAccountStr = tokens[3]
        val creditAccountStr = tokens[4]

        val amount = BigDecimal(amountStr)
        if (amount <= BigDecimal.ZERO) {
            throw Exception("Amount must be greater than zero.")
        }

        val debitAccount = resolveAccount(debitAccountStr)
            ?: throw Exception("Debit account not found: '$debitAccountStr'")
        val creditAccount = resolveAccount(creditAccountStr)
            ?: throw Exception("Credit account not found: '$creditAccountStr'")

        if (debitAccount.isPlaceholder || creditAccount.isPlaceholder) {
            throw Exception("Cannot post transactions to placeholder accounts.")
        }

        val commodity = debitAccount.commodity
        val money = Money(amount, commodity)

        val transaction = Transaction(description).apply {
            this.commodity = commodity
        }

        val debitSplit = Split(money, debitAccount.uid).apply {
            type = TransactionType.DEBIT
        }
        val creditSplit = Split(money, creditAccount.uid).apply {
            type = TransactionType.CREDIT
        }

        transaction.addSplit(debitSplit)
        transaction.addSplit(creditSplit)

        transactionsDbAdapter.addRecord(transaction)
        
        printOutput("Transaction created successfully!", LogType.SUCCESS)
        printOutput("  Desc: $description\n  Amount: ${money.formattedString()}\n  Debit: ${debitAccount.fullName ?: debitAccount.name}\n  Credit: ${creditAccount.fullName ?: creditAccount.name}", LogType.SUCCESS)
    }

    private fun executeSplit(tokens: List<String>) {
        if (tokens.size < 8 || (tokens.size - 2) % 3 != 0) {
            printOutput("Usage: split <description> <account_1> <amount_1> <type_1> <account_2> <amount_2> <type_2> ...", LogType.ERROR)
            return
        }

        val description = tokens[1]
        val splitDataList = mutableListOf<ParsedSplit>()
        var i = 2
        while (i < tokens.size) {
            val accountStr = tokens[i]
            val amountStr = tokens[i + 1]
            val typeStr = tokens[i + 2]

            val account = resolveAccount(accountStr)
                ?: throw Exception("Account not found: '$accountStr'")
            if (account.isPlaceholder) {
                throw Exception("Cannot post to placeholder account: '${account.fullName ?: account.name}'")
            }

            val amount = BigDecimal(amountStr)
            if (amount <= BigDecimal.ZERO) {
                throw Exception("Amount must be greater than zero: '$amountStr'")
            }

            val type = when (typeStr.lowercase()) {
                "d", "debit" -> TransactionType.DEBIT
                "c", "credit" -> TransactionType.CREDIT
                else -> throw Exception("Invalid split type '$typeStr'. Expected 'd', 'debit', 'c', or 'credit'")
            }

            splitDataList.add(ParsedSplit(account, amount, type))
            i += 3
        }

        // Check double-entry balancing
        var debitSum = BigDecimal.ZERO
        var creditSum = BigDecimal.ZERO
        for (s in splitDataList) {
            if (s.type == TransactionType.DEBIT) {
                debitSum = debitSum.add(s.amount)
            } else {
                creditSum = creditSum.add(s.amount)
            }
        }

        if (debitSum.compareTo(creditSum) != 0) {
            throw Exception("Transaction does not balance! Total Debits = $debitSum, Total Credits = $creditSum (diff of ${(debitSum - creditSum).abs()})")
        }

        val transaction = Transaction(description)
        val defaultAccount = splitDataList.first().account
        val commodity = defaultAccount.commodity
        transaction.commodity = commodity

        for (s in splitDataList) {
            val money = Money(s.amount, s.account.commodity)
            val split = Split(money, s.account.uid).apply {
                type = s.type
            }
            transaction.addSplit(split)
        }

        transactionsDbAdapter.addRecord(transaction)

        printOutput("Split transaction created successfully!", LogType.SUCCESS)
        val sb = StringBuilder("  Desc: $description\n  Splits:\n")
        val formatter = NumberFormat.getCurrencyInstance(Locale.getDefault())
        for (s in splitDataList) {
            val typeLabel = if (s.type == TransactionType.DEBIT) "DEBIT" else "CREDIT"
            sb.append("    ${s.account.fullName ?: s.account.name}: ${formatter.format(s.amount)} ($typeLabel)\n")
        }
        printOutput(sb.toString().trimEnd(), LogType.SUCCESS)
    }

    private fun resolveAccount(name: String): Account? {
        val all = accountsDbAdapter.allRecords
        
        // 1. Exact match (case insensitive)
        val exactMatch = all.firstOrNull { 
            it.fullName?.equals(name, ignoreCase = true) == true || 
            it.name.equals(name, ignoreCase = true) 
        }
        if (exactMatch != null) return exactMatch

        // 2. Substring match
        val matches = all.filter { 
            it.fullName?.contains(name, ignoreCase = true) == true || 
            it.name.contains(name, ignoreCase = true) 
        }
        if (matches.size == 1) {
            return matches[0]
        } else if (matches.size > 1) {
            val options = matches.take(5).joinToString(", ") { "'${it.fullName ?: it.name}'" }
            throw Exception("Ambiguous account name '$name'. Matches: $options")
        }
        return null
    }

    private fun updateSuggestions(text: String) {
        val cursor = commandInput.selectionStart
        if (cursor < 0) {
            suggestionRecycler.visibility = View.GONE
            return
        }

        var start = cursor
        while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] != '"') {
            start--
        }
        var end = cursor
        while (end < text.length && !text[end].isWhitespace() && text[end] != '"') {
            end++
        }

        val currentWord = text.substring(start, end).trim()

        if (currentWord.length >= 2) {
            val allAccounts = accountsDbAdapter.allRecords
            val matches = allAccounts.filter {
                !it.isPlaceholder &&
                (it.fullName?.contains(currentWord, ignoreCase = true) == true ||
                 it.name.contains(currentWord, ignoreCase = true))
            }.map { it.fullName ?: it.name }.take(6)

            if (matches.isNotEmpty()) {
                suggestionList.clear()
                suggestionList.addAll(matches)
                suggestionAdapter.notifyDataSetChanged()
                suggestionRecycler.visibility = View.VISIBLE
                return
            }
        }

        suggestionRecycler.visibility = View.GONE
    }

    private fun completeSuggestion(suggestion: String) {
        val text = commandInput.text.toString()
        val cursor = commandInput.selectionStart
        if (cursor < 0) return

        var start = cursor
        while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] != '"') {
            start--
        }
        var end = cursor
        while (end < text.length && !text[end].isWhitespace() && text[end] != '"') {
            end++
        }

        val before = text.substring(0, start)
        val after = text.substring(end)

        val completed = if (suggestion.contains(" ")) "\"$suggestion\"" else suggestion

        commandInput.setText("$before$completed$after")
        commandInput.setSelection(start + completed.length)
        suggestionRecycler.visibility = View.GONE
    }

    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val currentToken = java.lang.StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (c.isWhitespace() && !inQuotes) {
                if (currentToken.isNotEmpty()) {
                    tokens.add(currentToken.toString())
                    currentToken.setLength(0)
                }
            } else {
                currentToken.append(c)
            }
            i++
        }
        if (currentToken.isNotEmpty()) {
            tokens.add(currentToken.toString())
        }
        return tokens
    }

    companion object {
        fun newInstance() = ConsoleFragment()
    }

    // --- Log Recycler Adapter ---
    inner class LogAdapter(private val items: List<LogEntry>) :
        RecyclerView.Adapter<LogAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.log_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_console_log, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.text.text = item.text
            val color = when (item.type) {
                LogType.INPUT -> ContextCompat.getColor(holder.itemView.context, R.color.abs__holo_blue_light)
                LogType.OUTPUT -> ContextCompat.getColor(holder.itemView.context, android.R.color.white)
                LogType.SUCCESS -> ContextCompat.getColor(holder.itemView.context, R.color.credit_green)
                LogType.ERROR -> ContextCompat.getColor(holder.itemView.context, R.color.debit_red)
            }
            holder.text.setTextColor(color)
        }

        override fun getItemCount(): Int = items.size
    }

    // --- Suggestion Recycler Adapter ---
    inner class SuggestionAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SuggestionAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.suggestion_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_console_suggestion, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.text.text = item
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
