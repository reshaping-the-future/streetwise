package ac.robinson.streetwise;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ListView;

public class StoppableListView extends ListView {
	public StoppableListView(Context context) {
		super(context);
	}

	public StoppableListView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public StoppableListView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	int mPosition;
	boolean mStopScrolling;

	void setStopScrolling(boolean stopScrolling) {
		mStopScrolling = stopScrolling;
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent ev) {

		if (mStopScrolling) {
			final int actionMasked = ev.getActionMasked() & MotionEvent.ACTION_MASK;

			if (actionMasked == MotionEvent.ACTION_DOWN) {
				// record the position the list the touch landed on
				mPosition = pointToPosition((int) ev.getX(), (int) ev.getY());
				return super.dispatchTouchEvent(ev);
			}

			if (actionMasked == MotionEvent.ACTION_MOVE) {
				// ignore move events
				return true;
			}

			if (actionMasked == MotionEvent.ACTION_UP) {
				// check if we are still within the same view
				if (pointToPosition((int) ev.getX(), (int) ev.getY()) == mPosition) {
					super.dispatchTouchEvent(ev);
				} else {
					// clear pressed state, cancel the action
					setPressed(false);
					invalidate();
					return true;
				}
			}
		}
		return super.dispatchTouchEvent(ev);
	}
}
