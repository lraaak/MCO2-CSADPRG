import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.io.readCSV
import org.jetbrains.kotlinx.dataframe.io.writeCSV
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit


/**
 ********************
Last names: (Divinagracia)., Atienza, Lazaro, Paguila
Language: Kotlin
Paradigm(s): Procedural
 ********************
 */

fun main() {
    var df: AnyFrame? = null

    while (true) {
        println("===============================================")
        println("DPWH Flood Control Data Processing System")
        println("===============================================")
        println("[1] Load dataset")
        println("[2] Generate reports")
        println("[3] Exit")
        print("Enter your choice: ")

        when (readlnOrNull()?.trim()) {
            "1" -> df = loadDataset()
            "2" -> {
                if (df == null) println("Please load the dataset first (option [1]).")
                else generateReports(df)
            }
            "3" -> {
                println("Exiting system. Goodbye!")
                return
            }
            else -> println("Invalid input. Please select 1, 2, or 3.")
        }
        println()
    }
}


fun loadDataset(): AnyFrame {
    val file = File("dpwh_flood_control_projects.csv")
    if (!file.exists()) {
        println("File not found: ${file.absolutePath}")
        return DataFrame.Empty
    }

    println("Loading dataset...")
    var df = DataFrame.readCSV(file)
    println("Total rows loaded: ${df.rowsCount()}\n")


    df = df.convert("ApprovedBudgetForContract", "ContractCost").with { value ->
        value?.toString()?.trim()?.toDoubleOrNull()
    }


    df = df.convert("StartDate", "ActualCompletionDate").with { value ->
        val str = value?.toString()?.trim() ?: ""
        try {
            if (str.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) LocalDate.parse(str) else null
        } catch (_: Exception) {
            null
        }
    }


    val validRows = df.filter { row ->
        val budget = row["ApprovedBudgetForContract"] as? Double
        val cost = row["ContractCost"] as? Double
        val year = row["FundingYear"]?.toString()?.toIntOrNull()

        budget != null && cost != null && year != null && year in 2021..2023
    }

    val invalidRows = df.filter { it !in validRows.rows() }


    val outputDir = File("output")
    if (!outputDir.exists()) outputDir.mkdirs()

    validRows.writeCSV(File(outputDir, "valid_dataset.csv"))
    invalidRows.writeCSV(File(outputDir, "invalid_dataset.csv"))

    val kept = validRows.rowsCount()
    val removed = invalidRows.rowsCount()

    println("Records kept (valid): $kept")
    println("Records removed (invalid): $removed")
    println("Total processed: ${kept + removed}\n")


    val cleanDf = validRows
        .add("CostSavings") { row ->
            val budget = (row["ApprovedBudgetForContract"] as? Number)?.toDouble() ?: 0.0
            val cost = (row["ContractCost"] as? Number)?.toDouble() ?: 0.0
            budget - cost
        }
        .add("CompletionDelayDays") { row ->
            val start = row["StartDate"] as? LocalDate
            val end = row["ActualCompletionDate"] as? LocalDate
            if (start != null && end != null)
                ChronoUnit.DAYS.between(start, end)
            else 0L
        }

    println("Dataset ready for reporting.\n")
    return cleanDf
}



fun generateReports(df: AnyFrame) {
    val outputDir = File("output")
    if (!outputDir.exists()) outputDir.mkdirs()

    println("Generating reports...\n")

    val r1 = generateRegionalEfficiency(df)
    val r2 = generateContractorPerformance(df)
    val r3 = generateOverrunTrends(df)


    val totalProjects = df.rowsCount()
    val totalContractors = df
        .filter { row ->
            val year = (row["FundingYear"] as? Number)?.toInt()
            year != null && year in 2021..2023
        }
        .select("Contractor")
        .distinct()
        .rowsCount()
    val totalProvinces = df.distinct("Province").rowsCount()


    val avgDelay = df.rows()
        .mapNotNull { row -> (row["CompletionDelayDays"] as? Number)?.toDouble() }
        .average()


    val totalSavings = df.rows()
        .mapNotNull { row -> (row["CostSavings"] as? Number)?.toDouble() }
        .sum()


    val summaryData = mapOf(
        "total_projects" to totalProjects,
        "total_contractors" to totalContractors,
        "total_provinces" to totalProvinces,
        "average_delay_days" to String.format("%.2f", avgDelay),
        "total_savings" to String.format("%.2f", totalSavings)
    )


    val jsonText = buildString {
        append("{")
        summaryData.entries.forEachIndexed { i, e ->
            val comma = if (i < summaryData.size - 1) "," else ""
            append("""  "${e.key}": "${e.value}"$comma""")
        }
        append("}")
    }

    val summaryFile = File(outputDir, "summary.json")
    summaryFile.writeText(jsonText)

    println("summary.json created successfully in /output/\n")

    println("All reports successfully saved in /output/")
    println("===============================================")
}


fun generateRegionalEfficiency(df: AnyFrame): AnyFrame {
    println("Report 1: Regional Flood Mitigation Efficiency Summary")

    val grouped = df.groupBy("MainIsland", "Region").aggregate {
        sum("ApprovedBudgetForContract") into "Total Budget"
        median("CostSavings") into "MedianSavings"
        mean("CompletionDelayDays") into "AvgDelay"

        val delayed = count { row ->
            ((row["CompletionDelayDays"] as? Number)?.toLong() ?: 0L) > 30L
        }
        val total = count()
        val pct = if (total > 0) delayed * 100.0 / total else 0.0
        "%.2f%%".format(pct) into "HighDelayPct"
    }
        .add("EfficiencyScore") { row ->
            val med = (row["MedianSavings"] as? Number)?.toDouble() ?: 0.0
            val delay = (row["AvgDelay"] as? Number)?.toDouble() ?: 1.0
            if (delay == 0.0) 0.0 else (med / delay) * 100.0
        }
        .sortByDesc("EfficiencyScore")

    val formatted = grouped.convert("Total Budget", "MedianSavings", "AvgDelay", "HighDelayPct", "EfficiencyScore")
        .with { if (it is Number) String.format("%.2f", it.toDouble()) else it.toString() }

    formatted.writeCSV("output/report_regional_efficiency.csv")

    println("\nTop 5 Results:")
    printTable(formatted.take(5))
    println("→ Saved as output/report_regional_efficiency.csv\n")

    return grouped
}





fun generateContractorPerformance(df: AnyFrame): AnyFrame {
    println("Report 2: Top Contractors Performance Ranking")

    val grouped = df.groupBy("Contractor").aggregate {
        count() into "ProjectCount" into "NumProjects"
        sum("ContractCost") into "TotalCost"
        sum("CostSavings") into "TotalSavings"
        mean("CompletionDelayDays") into "AvgDelay"
    }

        .filter { (it["ProjectCount"] as? Int ?: 0) >= 5 }


        .add("ReliabilityIndex") {
            val avgDelay = (it["AvgDelay"] as? Number)?.toDouble() ?: 0.0
            val totalSavings = (it["TotalSavings"] as? Number)?.toDouble() ?: 0.0
            val totalCost = (it["TotalCost"] as? Number)?.toDouble() ?: 1.0

            val value = (1 - (avgDelay / 90.0)) * (totalSavings / totalCost) * 100.0
            "%.2f".format(value)
        }

        .add("RiskFlag") {
            val reliability = (it["ReliabilityIndex"] as? Number)?.toDouble() ?: 0.0
            if (reliability < 50.0) "High Risk" else "Stable"
        }

        .convert("TotalCost").with { (it as? Number)?.toDouble() ?: 0.0 }


        .sortByDesc("TotalCost")


        .take(15).add("Rank") { it.index() + 1 }
        .select("Rank", "Contractor", "TotalCost", "ProjectCount", "AvgDelay", "TotalSavings", "ReliabilityIndex", "RiskFlag")


    val formatted = grouped.convert("TotalCost", "AvgDelay", "ReliabilityIndex", "RiskFlag")
                        .with { if (it is Number) String.format("%.2f", it.toDouble()) else it.toString() }

    formatted.writeCSV("output/report_contractor_ranking.csv")

    println("\nTop 5 Results:")
    printTable(formatted.take(5))
    println("→ Saved as output/report_contractor_ranking.csv\n")

    return grouped
}



fun generateOverrunTrends(df: AnyFrame): AnyFrame {
    println("Report 3: Annual Project Type Cost Overrun Trends")


    val grouped = df.groupBy("FundingYear", "TypeOfWork").aggregate {
        count() into "TotalProjects"
        mean("CostSavings") into "AvgSavings"


        val overrunCount = count {
            ((it["CostSavings"] as? Number)?.toDouble() ?: 0.0) < 0.0
        }
        val total = count()
        val rate = if (total > 0) overrunCount * 100.0 / total else 0.0
        rate into "OverrunRate"
    }


    val withYoY = grouped.add("YoYChangePct") { row ->
        val year = row["FundingYear"] as Int
        val type = row["TypeOfWork"]
        val avg = (row["AvgSavings"] as? Double) ?: 0.0

        if (year == 2021) 0.0

        val prevYearData = grouped.filter { it["FundingYear"] == (year - 1) && it["TypeOfWork"] == type }

        val prevAvgSavings = prevYearData.firstOrNull()?.get("AvgSavings") as? Double ?: 0.0

        if (prevAvgSavings == 0.0) 0.0
        else ((avg - prevAvgSavings) / prevAvgSavings) * 100.0
    }



    val sorted = withYoY.sortWith { r1, r2 ->
        val year1 = (r1["FundingYear"] as Int)
        val year2 = (r2["FundingYear"] as Int)
        val avg1 = (r1["AvgSavings"] as? Double) ?: 0.0
        val avg2 = (r2["AvgSavings"] as? Double) ?: 0.0

        when {
            year1 != year2 -> year1.compareTo(year2)
            else -> avg2.compareTo(avg1)
        }
    }

    val formatted = sorted.convert("AvgSavings", "OverrunRate", "YoYChangePct")
            .with { if (it is Number) String.format("%.2f", it.toDouble()) else it.toString() }

    formatted.writeCSV("output/report_annual_overruns.csv")

    println("\nTop 5 Results:")
    printTable(formatted.take(5))
    println("→ Saved as output/report_annual_overruns.csv\n")

    return sorted
}




fun printTable(df: AnyFrame) {
    val cols = df.columns().map { it.name() }
    val widths = cols.map { c -> maxOf(c.length, df[c].toList().take(5).maxOfOrNull { it.toString().length } ?: 0) }


    val header = cols.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString(" | ")
    println(header)
    println("-".repeat(header.length))


    for (row in df.take(5).rows()) {
        val line = cols.mapIndexed { i, c -> row[c].toString().padEnd(widths[i]) }.joinToString(" | ")
        println(line)
    }
    println()
}