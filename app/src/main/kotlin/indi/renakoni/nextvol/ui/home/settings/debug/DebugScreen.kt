package indi.renakoni.nextvol.ui.home.settings.debug

import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SectionHeader
import io.nightfish.lightnovelreader.api.ui.components.SettingsClickableEntry

@Composable
fun DebugScreen(
    onClickBack: () -> Unit,
    onClickQuery: (String) -> Unit,
    onClickOpenBook: (String) -> Unit,
    result: String
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { TopBar(onClickBack) }
        item { BookBlock(onClickOpenBook) }
        item { SqlBlock(onClickQuery, result) }
        item { CrashBlock() }
    }
}

@Composable
fun BookBlock(onClickOpenBook: (String) -> Unit) {
    var bookId by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            text = stringResource(R.string.debug_open_book)
        )
        OutlinedTextField(
            value = bookId,
            onValueChange = { bookId = it },
            label = { Text(stringResource(R.string.debug_book_key)) },
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 14.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = { onClickOpenBook(bookId) }) {
                Text(stringResource(R.string.action_open))
            }
        }
    }
}

@Composable
fun SqlBlock(onClickQuery: (String) -> Unit, result: String) {
    var sqlCommand by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            text = stringResource(R.string.debug_sql)
        )
        OutlinedTextField(
            value = sqlCommand,
            onValueChange = { sqlCommand = it },
            label = { Text(stringResource(R.string.debug_sql_statement)) },
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 14.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = { onClickQuery(sqlCommand) }) {
                Text(stringResource(R.string.debug_run))
            }
        }
        Text(
            text = result,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}

@Composable
fun CrashBlock() {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            text = stringResource(R.string.debug_crash_tests)
        )
        SettingsClickableEntry(
            title = stringResource(R.string.debug_looper_crash),
            description = "Looper.getMainLooper().quit()",
            onClick = { Looper.getMainLooper().quit() }
        )
        SettingsClickableEntry(
            title = stringResource(R.string.debug_null_crash),
            description = "NullPointerException",
            onClick = { throw NullPointerException() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    onClickBack: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.debug_settings), style = typography.displayLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            IconButton(onClickBack) {
                Icon(
                    painterResource(id = R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.sources_back)
                )
            }
        },
    )
}
