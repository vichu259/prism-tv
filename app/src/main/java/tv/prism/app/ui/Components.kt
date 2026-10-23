package tv.prism.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.SubcomposeAsyncImage
import java.text.DateFormat
import java.util.Date

object Palette {
    val Bg = Color(0xFF000000)
    val BgTop = Color(0xFF000000)
    val Panel = Color(0xFF111111)
    val PanelHi = Color(0xFF1E1E1E)
    val Text = Color(0xFFF5F5F5)
    val Muted = Color(0xFF8A8A8A)
    val Accent = Color(0xFF8B7CFF)
    val AccentWarm = Color(0xFFB57CFF)
    val Fav = Color(0xFFFFC145)
    val Error = Color(0xFFFF6B6B)
}

/** Pure black backdrop: looks best on TVs (true black on OLED) and costs nothing. */
fun Modifier.appBackground(): Modifier = this.background(Palette.Bg)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
fun PrismTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            background = Palette.Bg,
            surface = Palette.Panel,
            onSurface = Palette.Text,
            onBackground = Palette.Text,
            inverseSurface = Palette.Text,
            inverseOnSurface = Palette.Bg,
            error = Palette.Error,
        ),
        content = content,
    )
}

/** Channel logo with a lettered fallback; portals often serve dead or missing logo URLs. */
@Composable
fun Logo(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Palette.PanelHi),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().take(2).uppercase(),
                color = Palette.Muted,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (url == null) {
        Box(modifier) { fallback() }
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)),
        loading = { fallback() },
        error = { fallback() },
    )
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(color))
    }
}

/**
 * D-pad friendly text field. Moving focus over it only highlights it; OK opens the TV keyboard.
 * (On many TVs a raw text field pops the keyboard on mere focus, which then swallows the D-pad.)
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Uri,
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Own the cursor: when the caller reformats the text (e.g. inserts MAC colons), keep it at the end.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))

    LaunchedEffect(editing) {
        if (editing) {
            // Select everything so typing replaces a prefilled value (default URL, random MAC)
            // instead of appending to it; Backspace clears it in one press.
            field = field.copy(selection = TextRange(0, field.text.length))
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }

    Column(modifier) {
        if (label.isNotEmpty()) Text(label, color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (focused) Palette.PanelHi else Palette.Panel)
                .border(2.dp, if (focused) Palette.Accent else Color.Transparent, RoundedCornerShape(10.dp))
                .focusRequester(boxFocus)
                .onFocusChanged { focused = it.hasFocus }
                .clickable { editing = true }
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Palette.Muted.copy(alpha = 0.6f), fontSize = 18.sp)
            BasicTextField(
                value = field,
                onValueChange = { field = it; onValueChange(it.text) },
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 18.sp),
                cursorBrush = SolidColor(if (editing) Palette.Accent else Color.Transparent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    editing = false
                    boxFocus.requestFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged { if (!it.isFocused && editing) editing = false },
            )
        }
    }
}

private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
fun clock(ms: Long): String = timeFormat.format(Date(ms))
