package dev.tgfeed.ui.floating;

import android.view.View;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import androidx.savedstate.SavedStateRegistryOwner;
import androidx.savedstate.ViewTreeSavedStateRegistryOwner;

/**
 * 悬浮窗 ComposeView 的 ViewTree 三件套接线（Java 实现）。
 *
 * 为什么用 Java 写：lifecycle 2.8.x 起 KMP 化，Kotlin 编译期按 KMP 元数据过滤可见 API，
 * ViewTreeLifecycleOwner 这类 Android 专属类在 Kotlin 侧会被滤成 Unresolved reference，
 * 而 javac 直接读 classes.jar 不受此过滤影响。悬浮窗的 ComposeView 挂在 WindowManager 上，
 * 没有 Activity/Fragment 供给树属主，必须手工补挂，否则 setContent 时抛 IllegalStateException。
 */
final class OverlayTreeWiring {

    private OverlayTreeWiring() {
    }

    /** 给指定 View 挂上生命周期/ViewModel/存档三位属主（通常三者是同一个对象）。 */
    static void wire(View view, LifecycleOwner lifecycleOwner,
                     ViewModelStoreOwner viewModelStoreOwner,
                     SavedStateRegistryOwner savedStateRegistryOwner) {
        ViewTreeLifecycleOwner.set(view, lifecycleOwner);
        ViewTreeViewModelStoreOwner.set(view, viewModelStoreOwner);
        ViewTreeSavedStateRegistryOwner.set(view, savedStateRegistryOwner);
    }
}
