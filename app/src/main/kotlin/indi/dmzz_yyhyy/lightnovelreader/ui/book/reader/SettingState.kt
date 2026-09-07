package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import indi.dmzz_yyhyy.lightnovelreader.data.setting.AbstractSettingState
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.data.MenuOptions
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope

@Suppress("MemberVisibilityCanBePrivate")
class SettingState(
    userDataRepository: UserDataRepository,
    coroutineScope: CoroutineScope
) : AbstractSettingState(coroutineScope), ReaderSettingsEditor, ThemeSettingsEditor {
    override val fontSizeUserData = userDataRepository.floatUserData(UserDataPath.Reader.FontSize.path)
    override val fontLineHeightUserData = userDataRepository.floatUserData(UserDataPath.Reader.FontLineHeight.path)
    override val fontWeighUserData = userDataRepository.floatUserData(UserDataPath.Reader.FontWeigh.path)
    override val keepScreenOnUserData = userDataRepository.booleanUserData(UserDataPath.Reader.KeepScreenOn.path)
    override val enableHideStatusBarUserData = userDataRepository.booleanUserData(UserDataPath.Reader.EnableHideStatusBar.path)
    override val enableBackgroundImageUserData = userDataRepository.booleanUserData(UserDataPath.Reader.EnableBackgroundImage.path)
    override val backgroundImageDisplayModeUserData = userDataRepository.stringUserData(UserDataPath.Reader.BackgroundImageDisplayMode.path)
    override val isUsingFlipPageUserData = userDataRepository.booleanUserData(UserDataPath.Reader.IsUsingFlipPage.path)
    override val isUsingClickFlipPageUserData = userDataRepository.booleanUserData(UserDataPath.Reader.IsUsingClickFlipPage.path)
    override val isUsingContinuousScrollingUserData = userDataRepository.booleanUserData(UserDataPath.Reader.IsUsingContinuousScrolling.path)
    override val isUsingVolumeKeyFlipUserData = userDataRepository.booleanUserData(UserDataPath.Reader.IsUsingVolumeKeyFlip.path)
    override val volumeKeyContinuousFlipIntervalUserData = userDataRepository.floatUserData(UserDataPath.Reader.VolumeKeyContinuousFlipInterval.path)
    override val flipAnimeUserData = userDataRepository.stringUserData(UserDataPath.Reader.FlipAnime.path)
    override val fastChapterChangeUserData = userDataRepository.booleanUserData(UserDataPath.Reader.FastChapterChange.path)
    override val batteryIndicatorDisplayModeUserData = userDataRepository.stringUserData(UserDataPath.Reader.BatteryIndicatorDisplayMode.path)
    override val enableTimeIndicatorUserData = userDataRepository.booleanUserData(UserDataPath.Reader.EnableTimeIndicator.path)
    override val enableChapterTitleIndicatorUserData = userDataRepository.booleanUserData(
        UserDataPath.Reader.EnableChapterTitleIndicator.path)
    override val enableReadingChapterProgressIndicatorUserData = userDataRepository.booleanUserData(
        UserDataPath.Reader.EnableReadingChapterProgressIndicator.path)
    override val autoPaddingUserData = userDataRepository.booleanUserData(UserDataPath.Reader.AutoPadding.path)
    override val topPaddingUserData = userDataRepository.floatUserData(UserDataPath.Reader.TopPadding.path)
    override val bottomPaddingUserData = userDataRepository.floatUserData(UserDataPath.Reader.BottomPadding.path)
    override val leftPaddingUserData = userDataRepository.floatUserData(UserDataPath.Reader.LeftPadding.path)
    override val rightPaddingUserData = userDataRepository.floatUserData(UserDataPath.Reader.RightPadding.path)
    override val textColorUserData = userDataRepository.colorUserData(UserDataPath.Reader.TextColor.path)
    override val textDarkColorUserData = userDataRepository.colorUserData(UserDataPath.Reader.TextDarkColor.path)
    override val fontFamilyUriUserData = userDataRepository.uriUserData(UserDataPath.Reader.FontFamilyUri.path)
    val fontFamilySettings: ReaderFontFamilySettings =
        UserDataReaderFontFamilySettings(fontFamilyUriUserData)
    override val backgroundColorUserData = userDataRepository.colorUserData(UserDataPath.Reader.BackgroundColor.path)
    override val backgroundDarkColorUserData = userDataRepository.colorUserData(UserDataPath.Reader.BackgroundDarkColor.path)
    override val backgroundImageUriUserData = userDataRepository.uriUserData(UserDataPath.Reader.BackgroundImageUri.path)
    override val backgroundDarkImageUriUserData = userDataRepository.uriUserData(UserDataPath.Reader.BackgroundDarkImageUri.path)
    override val darkModeKeyUserData = userDataRepository.stringUserData(UserDataPath.Settings.Display.DarkMode.path)
    override val dynamicColorsKeyUserData = userDataRepository.booleanUserData(UserDataPath.Settings.Display.DynamicColors.path)
    override val enableM3EUserData = userDataRepository.booleanUserData(UserDataPath.Settings.Display.EnableM3E.path)
    override val lightThemeNameUserData = userDataRepository.stringUserData(UserDataPath.Settings.Display.LightThemeName.path)
    override val darkThemeNameUserData = userDataRepository.stringUserData(UserDataPath.Settings.Display.DarkThemeName.path)
    override val backBlockModeUserData = userDataRepository.stringUserData(UserDataPath.Reader.BackBlockMode.path)

    override val fontSize by fontSizeUserData.safeAsState(15f)
    override val fontLineHeight by fontLineHeightUserData.safeAsState(7f)
    override val fontWeigh by fontWeighUserData.safeAsState(500f)
    override val keepScreenOn by keepScreenOnUserData.safeAsState(false)
    override val enableHideStatusBar by enableHideStatusBarUserData.safeAsState(true)
    override val enableBackgroundImage by enableBackgroundImageUserData.safeAsState(false)
    override val backgroundImageDisplayMode by backgroundImageDisplayModeUserData.safeAsState("fixed")
    override val isUsingFlipPage by isUsingFlipPageUserData.safeAsState(false)
    override val isUsingClickFlipPage by isUsingClickFlipPageUserData.safeAsState(false)
    override val isUsingContinuousScrolling by isUsingContinuousScrollingUserData.safeAsState(true)
    override val isUsingVolumeKeyFlip by isUsingVolumeKeyFlipUserData.safeAsState(false)
    override val volumeKeyContinuousFlipInterval by volumeKeyContinuousFlipIntervalUserData.safeAsState(-1f)
    override val flipAnime by flipAnimeUserData.safeAsState(MenuOptions.FlipAnimationOptions.ScrollWithoutShadow)
    override val fastChapterChange by fastChapterChangeUserData.safeAsState(false)
    override val batteryIndicatorDisplayMode by batteryIndicatorDisplayModeUserData.safeAsState("classic")
    override val enableTimeIndicator by enableTimeIndicatorUserData.safeAsState(true)
    override val enableChapterTitleIndicator by enableChapterTitleIndicatorUserData.safeAsState(true)
    override val enableReadingChapterProgressIndicator by enableReadingChapterProgressIndicatorUserData.safeAsState(true)
    override val autoPadding by autoPaddingUserData.safeAsState(true)
    override val topPadding by topPaddingUserData.safeAsState(12f)
    override val bottomPadding by bottomPaddingUserData.safeAsState(12f)
    override val leftPadding by leftPaddingUserData.safeAsState(16f)
    override val rightPadding by rightPaddingUserData.safeAsState(16f)
    override val textColor by textColorUserData.safeAsState(Color.Unspecified)
    override val textDarkColor by textDarkColorUserData.safeAsState(Color.Unspecified)
    override val fontFamilyUri by fontFamilyUriUserData.safeAsState(Uri.EMPTY)
    override val backgroundColor by backgroundColorUserData.safeAsState(Color.Unspecified)
    override val backgroundDarkColor by backgroundDarkColorUserData.safeAsState(Color.Unspecified)
    override val backgroundImageUri by backgroundImageUriUserData.safeAsState(Uri.EMPTY)
    override val backgroundDarkImageUri by backgroundDarkImageUriUserData.safeAsState(Uri.EMPTY)
    override val darkModeKey by darkModeKeyUserData.safeAsState("FollowSystem")
    override val dynamicColorsKey by dynamicColorsKeyUserData.safeAsState(false)
    override val enableM3E by enableM3EUserData.safeAsState(false)
    override val lightThemeName by lightThemeNameUserData.safeAsState("light_default")
    override val darkThemeName by darkThemeNameUserData.safeAsState("dark_default")
    override val backBlockMode by backBlockModeUserData.safeAsState("none")
}
