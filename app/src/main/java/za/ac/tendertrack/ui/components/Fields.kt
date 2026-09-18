package za.ac.tendertrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/**
 * Label, control, hint and error message in one place.
 *
 * Built on BasicTextField rather than OutlinedTextField so the height, padding
 * and border match the wireframes exactly instead of inheriting Material's.
 */
@Composable
fun AppTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    error: String? = null,
    leadingIcon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = Dimens.FieldHeight,
    enabled: Boolean = true
) {
    val borderColor = when {
        error != null -> AppColor.DangerBorder
        else -> AppColor.Line
    }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = AppType.Label)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(RoundedCornerShape(Dimens.RadiusField))
                .background(if (enabled) AppColor.Surface else AppColor.SurfaceMuted)
                .border(Dimens.BorderWidth, borderColor, RoundedCornerShape(Dimens.RadiusField))
                .padding(horizontal = 14.dp, vertical = if (singleLine) 0.dp else 14.dp),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    AppIcon(leadingIcon, tint = AppColor.MutedLight, size = 18.dp)
                    Spacer(Modifier.width(9.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = AppType.FieldValue.copy(color = AppColor.MutedLight)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = singleLine,
                        textStyle = AppType.FieldValue,
                        cursorBrush = SolidColor(AppColor.Ink),
                        visualTransformation = if (isPassword) PasswordVisualTransformation()
                        else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(5.dp))
            Text(error, style = AppType.Hint.copy(color = AppColor.DangerInk))
        } else if (hint != null) {
            Spacer(Modifier.height(5.dp))
            Text(hint, style = AppType.Hint)
        }
    }
}

/**
 * Dropdown with the same footprint as [AppTextField], so a form mixing text and
 * choice fields stays on one vertical rhythm.
 */
@Composable
fun <T> AppDropdownField(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select",
    hint: String? = null,
    error: String? = null,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = AppType.Label)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Dimens.FieldHeight)
                    .clip(RoundedCornerShape(Dimens.RadiusField))
                    .background(if (enabled) AppColor.Surface else AppColor.SurfaceMuted)
                    .border(
                        Dimens.BorderWidth,
                        if (error != null) AppColor.DangerBorder else AppColor.Line,
                        RoundedCornerShape(Dimens.RadiusField)
                    )
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected?.let(optionLabel) ?: placeholder,
                    style = AppType.FieldValue.copy(
                        color = if (selected == null) AppColor.MutedLight else AppColor.Ink
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                AppIcon(Icons.Default.KeyboardArrowDown, tint = AppColor.MutedLight)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(AppColor.Surface)
            ) {
                if (options.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Nothing available", style = AppType.Meta) },
                        onClick = { expanded = false }
                    )
                }
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option), style = AppType.Body) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(5.dp))
            Text(error, style = AppType.Hint.copy(color = AppColor.DangerInk))
        } else if (hint != null) {
            Spacer(Modifier.height(5.dp))
            Text(hint, style = AppType.Hint)
        }
    }
}

/** Search box that sits above a filtered list. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.SearchHeight)
            .clip(RoundedCornerShape(Dimens.RadiusField))
            .background(AppColor.Surface)
            .border(Dimens.BorderWidth, AppColor.Line, RoundedCornerShape(Dimens.RadiusField))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(Icons.Default.Search, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = AppType.FieldValue.copy(color = AppColor.MutedLight), maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = AppType.FieldValue,
                cursorBrush = SolidColor(AppColor.Ink),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Selectable filter chip. */
@Composable
fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(Dimens.RadiusPill))
            .background(if (selected) AppColor.Ink else AppColor.Surface)
            .border(
                Dimens.BorderWidth,
                if (selected) AppColor.Ink else AppColor.LineStrong,
                RoundedCornerShape(Dimens.RadiusPill)
            )
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            style = AppType.ButtonSmall.copy(
                color = if (selected) AppColor.OnInk else AppColor.InkSoft
            ),
            maxLines = 1
        )
    }
}
