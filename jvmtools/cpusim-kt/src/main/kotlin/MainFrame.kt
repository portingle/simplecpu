import java.awt.*
import java.awt.BorderLayout.*
import java.awt.Color.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import javax.swing.JOptionPane.QUESTION_MESSAGE
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.event.TableModelEvent
import javax.swing.event.TableModelListener
import javax.swing.plaf.metal.MetalScrollBarUI
import javax.swing.table.*

val enableMemIllustration = true
val enableRamTable = true
val enableRegTable = true
val enableProgTable = true

val RamSize = 65536

val recentUpdates = ObservableSet<Int>(HashSet())


// hack for testing ...
//val foo = (1..65535).forEach{ idx ->
//    recentUpdates.add( idx )
//}

val program = mutableListOf<Instruction>()

val ramModel = RamTableModel()

var registerTable: ScrollableJTable? = null
var currentInstView: JTable? = null
var progTable: ScrollableJTable? = null
var buttonsView: Component? = null
var ramTable: ScrollableJTable? = null

var ramIllustration : Pair<TableModel, JFrame>? = null

fun mkExecModel(): DefaultTableModel {
    val dtm = DefaultTableModel()
    dtm.addColumn("data")
    return dtm
}

val execModel = mkExecModel()

val regModel = RegModel(execModel)

fun addExec(exec: InstructionExec) {
    execModel.addRow(arrayOf(exec))
}

fun currentExec(): InstructionExec {
    return getExec(execModel.rowCount - 1)
}

fun getExec(row: Int): InstructionExec {
    try {
        if (execModel.rowCount > 0) {
            val r = execModel.getValueAt(row, 0);
            return r as InstructionExec
        }
        return InstructionExec(
            clk = 0,
            pc = 0,
            alu = 0,
            aval = 0,
            bval = 0,
            doExec = true,
            regIn = Registers(clk = 0, pc = 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            flagsIn = emptyList(),
            flagsOut = emptyList(),
            effectiveOp = Op.A
        )
    } catch (ex: Exception) {
        throw ex
    }
}

interface ColWidth {
    fun getWidth(col: Int): Int
}


fun simUI(): Debugger {
    return createAndShowGUI()
}

val clksRemaining = Semaphore(0)

private fun createAndShowGUI(): Debugger {

    val mainframe = MainFrame("SPAM-1 Simulator")
    mainframe.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    val mainPain = mainframe.contentPane

    val menuBar = JMenuBar()
    mainframe.jMenuBar = menuBar
    val menuFile = JMenu("File");
    val menuEdit = JMenu("Edit");
    menuBar.add(menuFile)
    menuBar.add(menuEdit)

    val menuLoad = JMenuItem("Load..")
    val menuSave = JMenuItem("Save..")
    menuFile.add(menuLoad)
    menuFile.add(menuSave)

    ramTable = createRamTable(regModel)
    registerTable = createRegisterView()
    progTable = createProgView()
    currentInstView = createCurrentInstView()
    buttonsView = createButtonsView()
    ramIllustration = createRamIllustration()

    if (ramTable != null) {
        mainPain.add(ramTable, WEST)
    }
    mainPain.add(object : BPanel() { init {
        add(object : BPanel() { init {
            if (registerTable != null) {
                add(
                    object : BPanel() { init {
                        add(registerTable, EAST)
                    }
                    },
                    NORTH
                )
            }
            add(
                object : BPanel() { init {
                    add(currentInstView, NORTH)
                    if (progTable != null) {
                        add(progTable, CENTER)
                    }
                }
                },
                CENTER
            )
            add(buttonsView, SOUTH)
        }
        })
    }
    }, EAST)

    mainframe.config()


    val dbg = object : Debugger {
        override fun instructions(code: List<Instruction>) {
            program.addAll(code)
        }

        override fun onDebug(code: InstructionExec, commit: () -> Unit) {
            addExecState(code)
            progTable?.repaint()

            clksRemaining.acquire()

            commit.invoke()

        }

        override fun observeRam(ram: ObservableList<Int>) {
            ram.addObserver(object : Observer {
                override fun update(o: Observable?, arg: Any?) {
                    val idx = arg as Int
                    val ramVal = ram.get(idx)
                    ramModel.setValueAt(ramVal, idx, 1) // col 1 is the ram value
                }
            })
        }

    }
    mainframe.repaint()
//    mainframe.hide()


    return dbg
//    val mmView = MainFrame("Memory Map")
//    mmView.contentPane.add(memoryGrid.second, CENTER)
//    mmView.config()
//    mmView.repaint()
}

val ByteStringsText = (0..255).map { "0x%02x %3d".format(it, it) }.toList().toTypedArray()

abstract class BPanel : JPanel() {
    init {
        layout = BorderLayout()
    }
}

class MainFrame(title: String) : JFrame() {

    init {
        createUI(title)
    }

    private fun createUI(title: String) {

        setTitle(title)

        setLocationRelativeTo(null)

        layout = BorderLayout()

    }

    fun config() {

        val w = components.sumOf { it.preferredSize.width }
        val h = components.maxOf { it.preferredSize.height }

        size = Dimension(w, h)
        positionCentrally(this)
        pack();
        isVisible = true
    }
}


fun createRegisterView(): ScrollableJTable? {

    if (!enableRegTable) {
        return null
    }

    val table = JTable(regModel)
    execModel.addTableModelListener { e -> regModel.fireTableDataChanged() }

    val tab = ScrollableJTable(table)

    regModel.cols.forEachIndexed { i, w ->
        tab.table.columnModel.getColumn(i).preferredWidth = regModel.getWidth(i)
    }
    val sz = 20 + tab.table.columnModel.columns.toList().sumOf { it.preferredWidth }
    tab.preferredSize = Dimension(sz, 300)

    return tab
}

val asmText = JTextField("Assembly")

fun createButtonsView(): Component {

    var pane = JPanel()
    val layout = GroupLayout(pane)
    pane.layout = layout

    layout.setAutoCreateGaps(true)
    layout.setAutoCreateContainerGaps(true)

    val asmLabel = JLabel("ASM")
    asmLabel.font = asmLabel.font.deriveFont(20.0f).deriveFont(Font.PLAIN)

    asmText.font = Font(Font.MONOSPACED, Font.PLAIN, 20)
    asmText.preferredSize = Dimension(300, 20)
    asmText.isEditable = false

    val nextBtn = JButton("Next")
    val runBtn = JButton("Run")

    val labelStep = JLabel("Advance Clocks")

    val IntFilter = object : KeyAdapter() {
        override fun keyTyped(e: KeyEvent) {
            val c = e.keyChar
            if ((c < '0' || c > '9') && c.code != KeyEvent.VK_BACK_SPACE) {
                e.consume() // if it's not a number, ignore the event
            }
        }
    }

    val textStep = JTextField("1000000")
    textStep.addKeyListener(IntFilter)
    textStep.maximumSize = Dimension(50, 30)
    textStep.preferredSize = Dimension(50, 30)
    textStep.size = Dimension(50, 30)

    val labelBrkClk = JLabel("Break Clk")
    val textBrkClk = JTextField("")
    textBrkClk.addKeyListener(IntFilter)
    textBrkClk.maximumSize = Dimension(70, 30)
    textBrkClk.preferredSize = Dimension(70, 30)

    val labelBrkPC = JLabel("Break PC")
    val textBrkPC = JTextField("")
    textBrkPC.addKeyListener(IntFilter)
    textBrkPC.maximumSize = Dimension(70, 30)
    textBrkPC.preferredSize = Dimension(70, 30)

    val resetRecentUpdates = JButton("Reset Recent")
    resetRecentUpdates.addActionListener { a ->
        recentUpdates.clear()
        ramTable?.repaint()
        if (ramIllustration != null) {
            ramIllustration!!.second.repaint()
        }
    }

    layout.setHorizontalGroup(
        layout.createParallelGroup(GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup().addComponent(asmLabel).addComponent(asmText))
            .addGroup(
                layout.createSequentialGroup().addComponent(nextBtn).addComponent(runBtn).addGap(30)
                    .addComponent(labelStep).addComponent(textStep).addGap(30)
                    .addComponent(labelBrkClk).addComponent(textBrkClk).addGap(30)
                    .addComponent(labelBrkPC).addComponent(textBrkPC).addGap(30)
                    .addComponent(resetRecentUpdates).addGap(100)
            )
    )

    layout.setVerticalGroup(
        layout.createSequentialGroup()
            .addGroup(
                layout.createParallelGroup(GroupLayout.Alignment.BASELINE).addComponent(asmLabel).addComponent(asmText)
            )
            .addGroup(
                layout.createParallelGroup().addComponent(nextBtn).addComponent(runBtn)
                    .addComponent(labelStep).addComponent(textStep)
                    .addComponent(labelBrkClk).addComponent(textBrkClk)
                    .addComponent(labelBrkPC).addComponent(textBrkPC)
                    .addComponent(resetRecentUpdates)
            )
    )

    pane.preferredSize = Dimension(1000, 100)

    nextBtn.addActionListener { a ->
        clksRemaining.release(1)
    }
    runBtn.addActionListener { a ->
        clksRemaining.release((textStep.text ?: "0").toInt())
    }

    return pane
}

val instNames = listOf(
    ColDef("PC", 70),
    ColDef("Targ", 70),
    ColDef("Left", 70),
    ColDef("Operation", 110),
    ColDef("Right", 70),
    ColDef("SetF", 40),
    ColDef("Cond", 60),
    ColDef("Inv", 30),
    ColDef("aMode", 50),
    ColDef("Address", 70),
    ColDef("Immed", 70),
)

fun createCurrentInstView(): JTable {

    val progModel = object : AbstractTableModel() {

        override fun getColumnCount(): Int {
            return instNames.size
        }

        override fun getRowCount(): Int {
            return 1
        }

        override fun getValueAt(row: Int, col: Int): Any {

            val currentExec: InstructionExec = currentExec()
            val pc = currentExec.pc
            val i = program[pc]

            val cd = instNames[col]
            val nameLower = cd.name.lowercase()

            return when (nameLower) {
                "pc" -> {
                    "<html>%d<br/>%s</html>".format(pc, if (currentExec.doExec) "exec .." else "skip ..")
                }
                "targ" -> {
                    "<html>%s<br/>0x%02x %3d</html>".format(i.t.name, currentExec.alu, currentExec.alu)
                }
                "left" -> {
                    "<html>%s<br/>0x%02x %3d</html>".format(i.a.name, currentExec.aval, currentExec.aval)
                }
                "right" -> {
                    "<html>%s<br/>0x%02x %3d</html>".format(i.b.name, currentExec.bval, currentExec.bval)
                }
                "operation" -> {
                    if (i.aluOp != currentExec.effectiveOp && row == 0) "<html>%s<br/>%s</html>".format(
                        i.aluOp.name,
                        currentExec.effectiveOp
                    )
                    else i.aluOp.name
                }
                "setf" -> {
                    i.setFlags.name
                }
                "amode" -> {
                    i.amode.name
                }
                "cond" -> {
                    val flagsIn = currentExec.flagsIn
                    "<html>%s<br/>%s</html>".format(
                        if (i.condition == Cond.A) "*" else i.condition.name,
                        flagsIn.filter { it != Cond.A }.joinToString(" ")
                    )
                }
                "inv" -> {
                    i.conditionInvert.name
                }
                "address" -> {
                    "0x%04x %5d".format(i.address, i.address)
                }
                "immed" -> {
                    "0x%02x %3d".format(i.immed, i.immed)
                }
                else -> {
                    "bad column " + nameLower
                }
            }
        }

        override fun getColumnName(column: Int): String {
            return instNames[column].name
        }
    }

    val table = JTable(progModel)
    table.font = Font(Font.MONOSPACED, Font.BOLD, 12)
    execModel.addTableModelListener(table)

    instNames.forEachIndexed { i, w ->
        table.columnModel.getColumn(i).preferredWidth = w.width
    }
    table.columnModel.getColumn(instNames.size - 1).preferredWidth += 20// stretch the last col where the scroll bar would have been

    val sz = table.columnModel.columns.toList().sumOf { it.preferredWidth }
    table.preferredSize = Dimension(sz, 35)

    table.setRowHeight(35);//Try set height to 15 (I've tried higher)

    table.background = Color(200, 255, 200)
    table.border = BorderFactory.createLineBorder(Color.BLUE)

    // align cell contents to top
    val topAlign = DefaultTableCellRenderer()
    topAlign.verticalAlignment = JLabel.TOP
    table.columnModel.columns.iterator().forEach { c -> c.cellRenderer = topAlign }

    return table
}

fun createProgView(): ScrollableJTable? {

    if (!enableProgTable) {
        return null
    }

    val progModel = object : AbstractTableModel() {

        override fun getColumnCount(): Int {
            return instNames.size
        }

        override fun getRowCount(): Int {
            return program.size
        }

        override fun getValueAt(row: Int, col: Int): Any {
            val pos = row
            val i = program[pos]

            val cd = instNames[col]
            val nameLower = cd.name.lowercase()

            return when (nameLower) {
                "pc" -> {
                    row.toString()
                }
                "targ" -> {
                    i.t.name
                }
                "left" -> {
                    i.a.name
                }
                "right" -> {
                    i.b.name
                }
                "operation" -> {
                    i.aluOp.name
                }
                "setf" -> {
                    i.setFlags.name
                }
                "amode" -> {
                    i.amode.name
                }
                "cond" -> {
                    i.condition.name
                }
                "inv" -> {
                    i.conditionInvert.name
                }
                "address" -> {
                    "0x%04x %5d".format(i.address, i.address)
                }
                "immed" -> {
                    "0x%02x %3d".format(i.immed, i.immed)
                }
                else -> {
                    "bad column " + nameLower
                }
            }
        }

        override fun getColumnName(column: Int): String {
            return instNames[column].name
        }
    }

    val table = JTable(progModel)
    val tab = ScrollableJTable(table)

    instNames.forEachIndexed { i, w ->
        table.columnModel.getColumn(i).preferredWidth = w.width + 21
    }
    table.columnModel.getColumn(instNames.size - 1).preferredWidth += 20 // stretch the last col where the scroll bar would have been
    val sz = table.columnModel.columns.toList().sumOf { it.preferredWidth }
    tab.preferredSize = Dimension(sz, 250)

    return tab
}

fun createRamTable(regModel: TableModel): ScrollableJTable? {

    if (enableRamTable) {
        val table = object : JTable() {
            override fun prepareRenderer(
                renderer: TableCellRenderer?,
                row: Int,
                column: Int
            ): Component? {

                val addr = row
                val invAddr = RamSize - row - 1

                val c = super.prepareRenderer(
                    renderer,
                    row, column
                )

                var bgIsRed = false

                val isValue = getColumnName(column) == "value"
                if (isValue && recentUpdates.contains(addr)) {
                    c.background = RED
                    bgIsRed = true
                } else {
                    val isAddr = getColumnName(column) == "address"
                    val marValue = currentExec().regIn.mar
                    val isMarRow = invAddr == marValue
                    if (isAddr && isMarRow) {
                        c.background = YELLOW
                    } else {
                        c.background = WHITE
                    }
                }

                if (invAddr == program[currentExec().pc].address) {
                    if (bgIsRed) c.foreground = BLACK
                    else c.foreground = RED
                    c.font = c.font.deriveFont(Font.BOLD)
//                c.graphics.color = BLUE
//                c.graphics.drawRect(c.bounds.x, c.bounds.y, c.bounds.width, c.bounds.height)
                } else {
                    c.font = c.font.deriveFont(Font.PLAIN)
                    c.foreground = BLACK
//                c.graphics.clearRect(c.bounds.x, c.bounds.y, c.bounds.width, c.bounds.height)
                }
                return c
            }
        }

        val tml = object : TableModelListener {
            override fun tableChanged(e: TableModelEvent?) {
                ramModel.fireTableDataChanged()
            }
        }

        regModel.addTableModelListener(tml)
        execModel.addTableModelListener(tml)

        table.model = ramModel


        // turn it into a scrollable table
        val tab = ScrollableJTable(table, RamScrollBar(ramModel))

        tab.table.columnModel.getColumn(1).cellEditor = DialogByteEditor("value")

        ramModel.cols.forEachIndexed { i, w ->
            tab.table.columnModel.getColumn(i).preferredWidth = ramModel.getWidth(i)
        }
        val sz = 20 + tab.table.columnModel.columns.toList().sumOf { it.preferredWidth }

        tab.preferredSize = Dimension(sz, 700)

        table.setAutoCreateRowSorter(true); // sorting of the rows on a particular column

        return tab
    } else {
        return null
    }
}


private fun positionCentrally(frame: MainFrame) {
// Determine the new location of the window
    val dim: Dimension = Toolkit.getDefaultToolkit().getScreenSize()

// Determine the new location of the window
    val w: Int = frame.getSize().width
    val h: Int = frame.getSize().height
    val x = (dim.width - w) / 2
    val y = (dim.height - h) / 2

    frame.setLocation(x, y);

}

class DialogByteEditor(val title: String) : AbstractCellEditor(), TableCellEditor, ActionListener {
    var newInput: Int = 0
    var oldValue: Int = 0
    var button: JButton


    init {
        button = JButton()
        button.background = Color.WHITE
        button.actionCommand = EDIT
        button.addActionListener(this)
        button.isBorderPainted = false
    }

    override fun actionPerformed(e: ActionEvent) {
        if (EDIT == e.actionCommand) {

            var selected = JOptionPane.showInputDialog(
                null,
                "Edit",
                title,
                QUESTION_MESSAGE,
                null,
                (0..255).toList().toTypedArray(),
                oldValue
            ) as Int?

            if (selected == -1) {
                selected = oldValue
            } else if (selected == null) {
                selected = oldValue
            }

            newInput = selected
            if (newInput != oldValue) fireEditingStopped()
            else fireEditingCanceled()
        }
    }

    override fun getCellEditorValue(): Any {
        return newInput!!
    }

    override fun getTableCellEditorComponent(
        table: JTable,
        value: Any,
        isSelected: Boolean,
        row: Int,
        column: Int
    ): Component {
        newInput = ByteStringsText.indexOf(value)
        oldValue = ByteStringsText.indexOf(value)
        return button
    }

    companion object {
        const val EDIT = "edit"
    }
}

class ObservableList<T>(val wrapped: MutableList<T>) : MutableList<T> by wrapped, Observable() {
    override fun add(element: T): Boolean {
        if (wrapped.add(element)) {
            setChanged()
            notifyObservers(wrapped.size - 1)
            return true
        }
        return false
    }

    override fun set(index: Int, element: T): T {
        var ret: T = wrapped.set(index, element)
        setChanged()
        notifyObservers(index)
        return ret
    }
}

class ObservableSet<T>(val wrapped: Set<T>) : Observable() {
    private val map = ConcurrentHashMap<T, T>()

    init {
        wrapped.forEach { v ->
            map.put(v, v)
        }
    }

    fun add(element: T): Boolean {
        if (!map.contains(element)) {
            map.put(element, element)
            setChanged()
            notifyObservers(element)
            return true
        }
        return false
    }

    fun contains(t: T) = map.contains(t)

    fun clear() {
        map.clear()
        setChanged()
        notifyObservers()
    }

    fun size() = map.size
//
//    override fun set(index: Int, element: T): T {
//        var ret: T = wrapped.set(index, element)
//        setChanged()
//        notifyObservers(index)
//        return ret
//    }
}

data class RamData(
    val address: Int,
    var value: Int = 0,
    var prev: Int = 0,
    var clk: Int = 0,
    var comment: String = ""
)

data class InstructionExec(
    val clk: Int,
    val pc: Int,
    val alu: Int,
    val aval: Int,
    val bval: Int,
    val doExec: Boolean,
    val regIn: Registers,
    val flagsIn: List<Cond>,
    val flagsOut: List<Cond>,
    val effectiveOp: Op
)

data class Registers(
    val clk: Int,
    val pc: Int,
    val pchitmp: Int,
    val pchi: Int,
    val pclo: Int,
    val marhi: Int,
    val marlo: Int,
    val rega: Int,
    val regb: Int,
    val regc: Int,
    val regd: Int,
    val portSel: Int,
    val timer1: Int,
    val halt: Int,
    val alu: Int
) {
    val mar = (marhi * 256) + marlo
}

data class ColDef(val name: String, val width: Int)

fun fmt2(i: Int) = "0x%02x %3d".format(i, i)

class RegModel(val execMode: TableModel) : AbstractTableModel(), ColWidth, TableModelListener {

    init {
        execModel.addTableModelListener(this)
    }

    val cols = listOf(
        ColDef("clk", 90),
        ColDef("pc", 90),
        ColDef("pchitmp", 60),
        ColDef("mar", 90),
        ColDef("rega", 60),
        ColDef("regb", 60),
        ColDef("regc", 60),
        ColDef("regd", 60),
        ColDef("portsel", 60),
        ColDef("timer1", 60),
        ColDef("halt", 60),
        ColDef("alu", 60),
        ColDef("flags", 110),
    )

    override fun getColumnCount() = cols.size

    override fun getColumnName(column: Int) = cols[column].name

    override fun getRowCount() = execModel.rowCount

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val colName = getColumnName(columnIndex).lowercase()
        val pos = execModel.rowCount - rowIndex - 1
        val data = getExec(pos)

        // Registers

        return when (colName) {
            "clk" -> {
                data.clk
            }
            "pc" -> {
                data.pc
                val mar = (data.regIn.pchi * 256) + data.regIn.pclo
                "0x%04x %5d".format(mar, mar)
            }
            "pchitmp" -> {
                fmt2(data.regIn.pchitmp)
            }
            "mar" -> {
                val mar = (data.regIn.marhi * 256) + data.regIn.marlo
                "0x%04x %5d".format(mar, mar)
            }
            "rega" -> {
                fmt2(data.regIn.rega)
            }
            "regb" -> {
                fmt2(data.regIn.regb)
            }
            "regc" -> {
                fmt2(data.regIn.regc)
            }
            "regd" -> {
                fmt2(data.regIn.regd)
            }
            "portsel" -> {
                fmt2(data.regIn.portSel)
            }
            "timer1" -> {
                fmt2(data.regIn.timer1)
            }
            "halt" -> {
                fmt2(data.regIn.halt)
            }
            "alu" -> {
                fmt2(data.regIn.alu)
            }
            "flags" -> {
                "%s".format(data.flagsOut.filter { it != Cond.A }.joinToString(" "))
            }
            else -> {
                "bad column " + colName
            }
        }
    }

    override fun getWidth(col: Int): Int = cols[col].width

    override fun tableChanged(e: TableModelEvent?) {
        fireTableDataChanged()
    }
}

open class RamTableModel : AbstractTableModel(), ColWidth {

    val data = (0..(RamSize - 1)).map { RamData(address = it) }.toMutableList()

    init {
        restore()
        if (ramIllustration != null) {
            recentUpdates.addObserver(object : Observer {
                override fun update(o: Observable?, arg: Any?) {
                    ramIllustration!!.second.repaint()
                }
            })
        }
    }

    val cols = listOf(
        ColDef("address", 90),
        ColDef("value", 60),
        ColDef("prev", 60),
        ColDef("clk", 90),
        ColDef("comment", 200),
    )

    fun save() {
        val txt = data
            .mapIndexed { idx, d -> Pair(idx, d.comment) }
            .filterNot { it.second.isEmpty() }
            .map { it.first.toString() + "=" + it.second }
            .joinToString("\n")
        File("comments.txt").writeText(txt)
    }

    fun restore() {
        val comments = File("comments.txt")
        if (comments.exists()) {
            comments.useLines { lines ->
                lines.toList().forEach {
                    val parts = it.split("=".toRegex(), 2)
                    val idx = parts[0].toInt()
                    val comment = parts[1]
                    data[idx].comment = comment
                }
            }
        }
    }

    override fun getColumnCount(): Int {
        return cols.size
    }

    override fun getRowCount(): Int {
        return RamSize
    }

    override fun getValueAt(rowIndex: Int, colIndex: Int): Any {
        val pos = RamSize - rowIndex - 1 // read with reverse order to write

        val i = data[pos]

        val colName = getColumnName(colIndex)
        return when (colName) {
            "address" -> "0x%04x %4d".format(i.address, i.address)
            "value" -> fmt2(i.value)
            "prev" -> fmt2(i.prev)
            "clk" -> i.clk
            "comment" -> i.comment
            else -> {
                "bad column " + colIndex
            }
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val pos = RamSize - rowIndex - 1 // read with reverse order to write

        val columnName = getColumnName(columnIndex)
        if (columnName == "value") {
            data[rowIndex].prev = data[rowIndex].value

            val update = aValue?.toString()?.toInt() ?: data[rowIndex].value
            data[rowIndex].value = update

            recentUpdates.add(pos)
        } else if (columnName == "comment") {
            data[pos].comment = aValue as String
        }
        data[rowIndex].clk = currentExec().clk

        this.fireTableCellUpdated(rowIndex, columnIndex)

        save()
    }


    override fun getColumnName(column: Int): String {
        return cols[column].name
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        return getColumnName(columnIndex).lowercase() == "value"
                || getColumnName(columnIndex).lowercase() == "comment"
    }

    override fun getWidth(col: Int): Int = cols[col].width
}


class RamScrollBar(val data: RamTableModel) : MetalScrollBarUI() {
    val self = this

    val obs = object : Observer {
        override fun update(o: Observable?, arg: Any?) {
            self.scrollbar.repaint()
        }
    }

    init {
        recentUpdates.addObserver(obs)
    }

    override fun paintTrack(g: Graphics, c: JComponent, trackBounds: Rectangle) {

        super.paintTrack(g, c, trackBounds)
        g.color = Color.RED

        for (i in recentUpdates.wrapped) {
            val pos = 1 - ((i * 1.0) / data.data.size)
            g.fillRect(0, trackBounds.y + (pos * trackBounds.height).toInt() - (5 / 2), trackBounds.width, 5)
        }
    }
}

class ScrollableJTable(val table: JTable, val scrollBarUI: RamScrollBar? = null) : JPanel() {

    init {
        initializeUI()
    }

    private fun initializeUI() {
        layout = BorderLayout()
        preferredSize = Dimension(400, 500)

        table.font = Font(Font.MONOSPACED, Font.PLAIN, 10)

        // Turn off JTable's auto resize so that JScrollPane will show a horizontal scroll bar.
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        val pane = JScrollPane(table)
        add(pane, CENTER)

        if (scrollBarUI != null) pane.verticalScrollBar.setUI(scrollBarUI)

        pane.horizontalScrollBarPolicy = HORIZONTAL_SCROLLBAR_NEVER
    }
}


fun addExecState(state: InstructionExec) {
    addExec(state)
    currentInstView?.repaint()
    registerTable?.repaint()

    // move PC row into middle of view
    if (progTable != null) {
        val tab = progTable?.table
        tab?.getSelectionModel()?.setSelectionInterval(state.pc, state.pc)
        val pageSize: Int = (tab!!.getParent()!!.getSize()!!.getHeight()!!.toInt() / (tab!!.getRowHeight()))
        val halfPage = pageSize / 2
        progTable?.table?.scrollRectToVisible(Rectangle(progTable?.table?.getCellRect(state.pc + halfPage, 0, true)));
    }

    // update disasm view
    asmText.text = disasm(program.get(state.pc))
}

class GridCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        col: Int
    ): Component {

        //Cells are by default rendered as a JLabel.
        val l = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col) as JLabel

        //Get the status for the current row.
        //val tableModel = table.model as DefaultTableModel
        if (col % 2 == 0) l.background = GREEN

        //Return the JLabel which renders the cell.
        return l
    }
}

fun createRamIllustration(): Pair<TableModel, JFrame>? {
    if (enableMemIllustration) {

        val memMap = object : AbstractTableModel() {
            override fun getRowCount(): Int {
                return 256
            }

            override fun getColumnCount(): Int {
                return 256
            }

            override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
                val offset = (rowIndex * 256) + columnIndex
                return recentUpdates.contains(offset)
            }
        }

        val pane = object : JFrame() {
            init {
                setVisible(false);
                title = "RAM View"
            }

            override fun paint(g: Graphics) {
                g.color = RED
                val topMargin = 50
                val leftMargin = 50
                val size = 1
                g.drawRect(leftMargin - 10, topMargin - 10, (256 * size) + 20, (256 * size) + 20);

                for (x in 0 until 256)
                    for (y in 0 until 256) {
                        if (x < memMap.columnCount && y < memMap.rowCount) {
                            if (memMap.getValueAt(y, x) as Boolean) {
                                g.fillRect(leftMargin + (size * x), topMargin + (size * y), size, size);
                            }
                        }
                    }
            }
        }

        //pane.size = Dimension(870, 880)
        pane.size = Dimension(370, 380)

        pane.setVisible(true);

        return Pair(memMap, pane)
    }

    return null
}
