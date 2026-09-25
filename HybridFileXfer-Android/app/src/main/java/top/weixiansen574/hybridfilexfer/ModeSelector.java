package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.core.content.ContextCompat;

/**
 * 替代 Spinner 的模式选择器。
 * 收起态就是一行文字+箭头(样式在布局里配),点击后用 ListPopupWindow 弹出列表:
 * 库自带组件 + 库自带下拉动画,外观与之前的 Spinner 弹出列表一致。
 * API 与 Spinner 同名(setSelection/getSelectedItemPosition/setOnItemSelectedListener),
 * 纯 UI 组件,不含任何业务逻辑。
 */
public class ModeSelector extends AppCompatTextView implements View.OnClickListener {

    private String[] modes;
    private int selectedPosition = 0;
    private AdapterView.OnItemSelectedListener onItemSelectedListener;
    private ListPopupWindow popup;

    public ModeSelector(Context context) {
        super(context);
        init();
    }

    public ModeSelector(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ModeSelector(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOnClickListener(this);
    }

    public void setModes(String[] modes) {
        this.modes = modes;
        if (modes != null && modes.length > 0) {
            setSelectionInternal(0, false);
        }
    }

    public void setSelection(int position) {
        setSelectionInternal(position, true);
    }

    private void setSelectionInternal(int position, boolean notify) {
        if (modes == null || position < 0 || position >= modes.length) {
            return;
        }
        selectedPosition = position;
        setText(modes[position]);
        if (notify && onItemSelectedListener != null) {
            onItemSelectedListener.onItemSelected(null, this, position, 0);
        }
    }

    public int getSelectedItemPosition() {
        return selectedPosition;
    }

    public void setOnItemSelectedListener(AdapterView.OnItemSelectedListener listener) {
        this.onItemSelectedListener = listener;
    }

    @Override
    public void onClick(View v) {
        showPopup();
    }

    private void showPopup() {
        if (modes == null || modes.length == 0) {
            return;
        }
        if (popup == null) {
            popup = new ListPopupWindow(getContext());
            popup.setAnchorView(this);
            popup.setModal(true);
            // 与框下沿衔接(-1dp,上边线与下划线重合),无阴影
            popup.setVerticalOffset(-Math.round(getResources().getDisplayMetrics().density));
            popup.setBackgroundDrawable(ContextCompat.getDrawable(getContext(), R.drawable.spinner_bg));
            // 库自带的标准弹出菜单动画(abc_popup_enter/exit,所有 AppCompat 弹出菜单同款)
            popup.setAnimationStyle(R.style.ModePopupAnimation);
            popup.setAdapter(new ArrayAdapter<>(getContext(), R.layout.item_mode_popup, modes));
            popup.setOnItemClickListener((parent, v, position, id) -> {
                dismissPopup();
                if (position != selectedPosition) {
                    setSelection(position);
                }
            });
        }
        // 宽度与框一致,左右边对齐
        popup.setWidth(getWidth());
        popup.show();
    }

    private void dismissPopup() {
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
        }
    }
}
