package com.xiaofeng.layoutmanagers;

import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.support.v7.widget.LinearSmoothScroller;
import android.support.v7.widget.RecyclerView;
import android.view.View;

import java.util.LinkedList;
import java.util.List;

/**
 * Layout manager for flow views. support different view height, support item add/removed notification
 * support align to left/right edge. support scroll/smooth scroll.
 */
public class FlowLayoutManager extends RecyclerView.LayoutManager {

	enum Alignment {
		LEFT,
		RIGHT
	}

	public static class FlowLayoutOptions {
		public static final int ITEM_PER_LINE_NO_LIMIT = 0;
		public Alignment alignment = Alignment.LEFT;
		public int itemsPerLine = ITEM_PER_LINE_NO_LIMIT;
		public static FlowLayoutOptions clone(FlowLayoutOptions layoutOptions) {
			FlowLayoutOptions result = new FlowLayoutOptions();
			result.alignment = layoutOptions.alignment;
			result.itemsPerLine = layoutOptions.itemsPerLine;
			return result;
		}
	}
	private static final String LOG_TAG = "FlowLayoutManager";
	RecyclerView recyclerView;
	int firstChildAdapterPosition = 0;
	RecyclerView.Recycler recyclerRef;
	FlowLayoutOptions flowLayoutOptions;
	FlowLayoutOptions newFlowLayoutOptions;

	public FlowLayoutManager() {
		flowLayoutOptions = new FlowLayoutOptions();
		newFlowLayoutOptions = FlowLayoutOptions.clone(flowLayoutOptions);
	}

	@Override
	public RecyclerView.LayoutParams generateDefaultLayoutParams() {
		return new RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT);
	}

	@Override
	public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
		recyclerRef = recycler;
		if (state.isPreLayout()) {
			onPreLayoutChildren(recycler);
		} else {
			onRealLayoutChildren(recycler);
		}
	}

	private void onPreLayoutChildren(RecyclerView.Recycler recycler) {
		// start from first view child
		int firstItemAdapterPosition = getChildAdapterPosition(0);
		int currentItemPosition = firstItemAdapterPosition < 0 ? 0 : firstItemAdapterPosition;
		Point point = layoutStartPoint(flowLayoutOptions);
		int x = point.x, y = point.y, height = 0;
		boolean newline;
		int real_x = point.x, real_y = point.y, real_height = 0;
		boolean real_newline;
		Rect rect = new Rect();
		Rect real_rect = new Rect();
		// detach all first.
		detachAndScrapAttachedViews(recycler);

		// this option use old options alignment & new options line limit to calc items for animation.
		FlowLayoutOptions options = FlowLayoutOptions.clone(flowLayoutOptions);
		options.itemsPerLine = newFlowLayoutOptions.itemsPerLine;

		// track before removed and after removed layout in same time, to make sure only add items at
		// bottom that visible after item removed.
		while (currentItemPosition < getItemCount()) {
			View child = recycler.getViewForPosition(currentItemPosition);
			boolean childRemoved = isChildRemoved(child);
			// act as removed view still there, to calc new items location.
			newline = calcChildLayoutRect(child, x, y, height,flowLayoutOptions, rect);
			if (newline) {
				point = startNewline(rect, flowLayoutOptions);
				x = point.x;
				y = point.y;
				height = rect.height();
			} else {
				x = advanceInSameLine(x, rect, flowLayoutOptions);
				height = Math.max(height, rect.height());
			}

			if (!childRemoved) {
				real_newline = calcChildLayoutRect(child, real_x, real_y, real_height, options, real_rect);
				if (real_newline) {
					point = startNewline(real_rect, options);
					real_x = point.x;
					real_y = point.y;
					real_height = real_rect.height();
				} else {
					real_x = advanceInSameLine(real_x, real_rect, options);
					real_height = Math.max(real_height, real_rect.height());
				}
			}

			// stop add new view if after removal, new items are not visible.
			if (!childVisible(real_x, real_y, real_x + rect.width(), real_y + rect.height())) {
				recycler.recycleView(child);
				break;
			} else {
				if (childRemoved) {
					addDisappearingView(child);
				} else {
					addView(child);
				}
				layoutDecorated(child, rect.left, rect.top, rect.right, rect.bottom);
			}
			currentItemPosition ++;
		}
		flowLayoutOptions = FlowLayoutOptions.clone(newFlowLayoutOptions);
	}

	private void onRealLayoutChildren(RecyclerView.Recycler recycler) {
		detachAndScrapAttachedViews(recycler);
		Point startPoint = layoutStartPoint();
		int x = startPoint.x, y = startPoint.y;
		int itemCount = getItemCount();
		int height = 0;
		boolean newLine;
		Rect rect = new Rect();
		for (int i = firstChildAdapterPosition; i < itemCount; i ++) {
			View child = recycler.getViewForPosition(i);
			newLine = calcChildLayoutRect(child, x, y, height, rect);
			if (!childVisible(rect)) {
				recycler.recycleView(child);
				return;
			} else {
				addView(child);
				layoutDecorated(child, rect.left, rect.top, rect.right, rect.bottom);
			}

			if (newLine) {
				Point lineInfo = startNewline(rect);
				x = lineInfo.x;
				y = lineInfo.y;
				height = rect.height();
			} else {
				x = advanceInSameLine(x, rect);
				height = Math.max(height, rect.height());
			}
		}
	}

	@Override
	public boolean canScrollHorizontally() {
		return false;
	}

	@Override
	public boolean canScrollVertically() {
		return true;
	}

	@Override
	public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
		if (dy == 0) {
			return 0;
		}
		if (getItemCount() == 0) {
			return 0;
		}

		return dy > 0? contentMoveUp(dy, recycler, state) : contentMoveDown(dy, recycler, state);
	}

	@Override
	public void onItemsChanged(RecyclerView recyclerView) {
		this.flowLayoutOptions = FlowLayoutOptions.clone(newFlowLayoutOptions);
		super.onItemsChanged(recyclerView);
	}

	/**
	 * Contents moving up to top
	 */
	private int contentMoveUp(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
		int actualDy = dy;
		int maxHeightIndex = getMaxHeightIndexInLine(getChildCount() - 1);
		View maxHeightItem = getChildAt(maxHeightIndex);
		int offscreenBottom = getDecoratedBottom(maxHeightItem) - bottomVisibleEdge();
		if (offscreenBottom >= dy) {
			offsetChildrenVertical(-dy);
			return dy;
		}
		while (getChildAdapterPosition(getChildCount() - 1) < getItemCount() - 1) {
			addNewLineAtBottom(recycler, state);
			maxHeightIndex = getMaxHeightIndexInLine(getChildCount() - 1);
			maxHeightItem = getChildAt(maxHeightIndex);
			offscreenBottom += getDecoratedMeasuredHeight(maxHeightItem);
			if (offscreenBottom >= dy) {
				break;
			}
		}

		if (offscreenBottom < dy) {
			actualDy = offscreenBottom;
		}
		offsetChildrenVertical(-actualDy);
		while (!lineVisible(0)) {
			recycleLine(0, recycler);
		}
		firstChildAdapterPosition = getChildAdapterPosition(0);
		return actualDy;
	}

	/**
	 * Contents move down to bottom
	 */
	private int contentMoveDown(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
		int actualDy = dy;
		int maxHeightItemIndex = getMaxHeightIndexInLine(0);
		View maxHeightItem = getChildAt(maxHeightItemIndex);
		int offScreenTop = topVisibleEdge() - getDecoratedTop(maxHeightItem);
		if (offScreenTop > Math.abs(actualDy)) {
			offsetChildrenVertical(-dy);
			return dy;
		}
		while (getChildAdapterPosition(0) > 0) {
			addNewLineAtTop(recycler);
			maxHeightItemIndex = getMaxHeightIndexInLine(0);
			maxHeightItem = getChildAt(maxHeightItemIndex);
			offScreenTop += getDecoratedMeasuredHeight(maxHeightItem);
			if (offScreenTop >= Math.abs(dy)) {
				break;
			}
		}

		if (offScreenTop < Math.abs(dy)) {
			actualDy = -offScreenTop;
		}

		offsetChildrenVertical(-actualDy);
		while (!lineVisible(getChildCount() - 1)) {
			recycleLine(getChildCount() - 1, recycler);
		}
		firstChildAdapterPosition = getChildAdapterPosition(0);
		return actualDy;
	}

	/**
	 * Add new line of elements at top, to keep layout, have to virtually layout from beginning.
	 */
	private void addNewLineAtTop(RecyclerView.Recycler recycler) {
		int x = layoutStartPoint().x, bottom = getDecoratedTop(getChildAt(getMaxHeightIndexInLine(0))), y;
		int height = 0;
		List<View> lineChildren = new LinkedList<>();
		int currentAdapterPosition = 0;
		int endAdapterPosition = getChildAdapterPosition(0) - 1;
		Rect rect = new Rect();
		boolean newline;
		boolean firstItem = true;
		while (currentAdapterPosition <= endAdapterPosition) {
			View newChild = recycler.getViewForPosition(currentAdapterPosition);

			newline = calcChildLayoutRect(newChild, x, 0, height, rect);

			// add view to make sure not be recycled.
			addView(newChild, lineChildren.size());
			if (newline && !firstItem) {
				// end of one line, but not reach the top line yet. recycle the line and
				// move on to next.
				for (View viewToRecycle : lineChildren) {
					removeAndRecycleView(viewToRecycle, recycler);
				}
				lineChildren.clear();
				x = advanceInSameLine(layoutStartPoint().x, rect);
				height = rect.height();
			} else {
				x = advanceInSameLine(x, rect);
				height = Math.max(height, rect.height());
				firstItem = false;
			}
			lineChildren.add(newChild);

			currentAdapterPosition ++;

		}

		x = layoutStartPoint().x;
		y = bottom - height;
		firstItem = true;

		for (int i = 0; i < lineChildren.size(); i ++) {
			View childView = lineChildren.get(i);
			newline = calcChildLayoutRect(childView, x, y, height, rect);
			if (newline && firstItem) {
				int rectHeight = rect.height();
				rect.top -= rectHeight;
				rect.bottom -= rectHeight;
				firstItem = false;
			}
			layoutDecorated(childView, rect.left, rect.top, rect.right, rect.bottom);
			x = advanceInSameLine(x, rect);
		}
	}

	/**
	 * Add new line at bottom of views.
	 */
	private void addNewLineAtBottom(RecyclerView.Recycler recycler, RecyclerView.State state) {
		int x = layoutStartPoint().x, y = getDecoratedBottom(getChildAt(getMaxHeightIndexInLine(getChildCount() - 1)));
		int right = x, bottom = y;
		int childAdapterPosition = getChildAdapterPosition(getChildCount() - 1) + 1;
		// no item to add
		if (childAdapterPosition == getItemCount()) {
			return;
		}
		Rect rect = new Rect();
		boolean newline;
		boolean firstItem = true;
		while (childAdapterPosition < getItemCount()) {
			View newChild = recycler.getViewForPosition(childAdapterPosition);
			newline = calcChildLayoutRect(newChild, x, y, 0, rect);
			if (newline && !firstItem) {
				recycler.recycleView(newChild);
				return;
			} else {
				addView(newChild);
				layoutDecorated(newChild, rect.left, rect.top, rect.right, rect.bottom);
				x = advanceInSameLine(x, rect);
				childAdapterPosition ++;
				firstItem = false;
			}
		}
	}

	@Override
	public void onAttachedToWindow(RecyclerView view) {
		super.onAttachedToWindow(view);
		this.recyclerView = view;
	}

	@Override
	public boolean supportsPredictiveItemAnimations() {
		return true;
	}

	@Override
	public void scrollToPosition(int position) {
		firstChildAdapterPosition = position;
		requestLayout();
	}

	@Override
	public void smoothScrollToPosition(final RecyclerView recyclerView, final RecyclerView.State state, int position) {
		RecyclerView.SmoothScroller smoothScroller = new LinearSmoothScroller(recyclerView.getContext()) {
			@Override
			public PointF computeScrollVectorForPosition(int targetPosition) {
				return new PointF(0, getOffsetOfItemToFirstChild(targetPosition, recyclerRef));
			}
		};
		smoothScroller.setTargetPosition(position);
		startSmoothScroll(smoothScroller);
	}

	private int leftVisibleEdge() {
		return getPaddingLeft();
	}

	private int rightVisibleEdge() {
		return getWidth() - getPaddingRight();
	}

	private int topVisibleEdge() {
		return getPaddingTop();
	}

	private int bottomVisibleEdge() {
		return getHeight() - getPaddingBottom();
	}

	private boolean childVisible(int left, int top, int right, int bottom) {
		return Rect.intersects(new Rect(leftVisibleEdge(), topVisibleEdge(), rightVisibleEdge(), bottomVisibleEdge()),
				new Rect(left, top, right, bottom));
	}

	private boolean childVisible(Rect childRect) {
		return Rect.intersects(new Rect(leftVisibleEdge(), topVisibleEdge(), rightVisibleEdge(), bottomVisibleEdge()), childRect);
	}

	private int getMaxHeightIndexInLine(int index) {
		final View child = getChildAt(index);
		int maxIndexBefore = index, maxIndexAfter = index, maxHeightBefore = getDecoratedMeasuredHeight(child), maxHeightAfter = getDecoratedMeasuredHeight(child);
		int currentIndex = index;
		while (currentIndex >= 0 && !isStartOfLine(currentIndex)) {
			final View beforeChild = getChildAt(currentIndex);
			if (getDecoratedMeasuredHeight(beforeChild) > maxHeightBefore) {
				maxIndexBefore = currentIndex;
				maxHeightBefore = getDecoratedMeasuredHeight(beforeChild);
			}
			currentIndex --;
		}
		// count in first one in line
		if (maxHeightBefore < getDecoratedMeasuredHeight(getChildAt(currentIndex))) {
			maxIndexBefore = currentIndex;
			maxHeightBefore = getDecoratedMeasuredHeight(getChildAt(currentIndex));
		}

		currentIndex = index;
		while (currentIndex < getChildCount() && !isEndOfLine(currentIndex)) {
			final View afterChild = getChildAt(currentIndex);
			if (getDecoratedMeasuredHeight(afterChild) > maxHeightAfter) {
				maxIndexAfter = currentIndex;
				maxHeightAfter = getDecoratedMeasuredHeight(afterChild);
			}
			currentIndex ++;
		}
		// count in last one in line
		if (maxHeightAfter < getDecoratedMeasuredHeight(getChildAt(currentIndex))) {
			maxIndexAfter = currentIndex;
			maxHeightAfter = getDecoratedMeasuredHeight(getChildAt(currentIndex));
		}
		if (maxHeightBefore >= maxHeightAfter) {
			return maxIndexBefore;
		}
		return maxIndexAfter;
	}

	private List<View> getAllViewsInLine(int index) {
		int firstItemIndex = index;
		while(!isStartOfLine(firstItemIndex)) {
			firstItemIndex --;
		}

		List<View> viewList = new LinkedList<>();
		viewList.add(getChildAt(firstItemIndex));
		int nextItemIndex = firstItemIndex + 1;
		while (nextItemIndex < getChildCount() && !isStartOfLine(nextItemIndex)) {
			viewList.add(getChildAt(nextItemIndex));
			nextItemIndex ++;
		}
		return viewList;
	}

	private int getChildAdapterPosition(int index) {
		return getChildAdapterPosition(getChildAt(index));
	}

	private int getChildAdapterPosition(View child) {
		return ((RecyclerView.LayoutParams)child.getLayoutParams()).getViewAdapterPosition();
	}

	public int getChildLayoutPosition(View child) {
		return ((RecyclerView.LayoutParams)child.getLayoutParams()).getViewLayoutPosition();
	}

	private boolean lineVisible(int index) {
		int maxHeightItemIndex = getMaxHeightIndexInLine(index);
		View maxHeightItem = getChildAt(maxHeightItemIndex);
		return Rect.intersects(new Rect(leftVisibleEdge(), topVisibleEdge(), rightVisibleEdge(), bottomVisibleEdge()),
				new Rect(leftVisibleEdge(), getDecoratedTop(maxHeightItem), rightVisibleEdge(), getDecoratedBottom(maxHeightItem)));
	}

	private void recycleLine(int index, RecyclerView.Recycler recycler) {
		List<View> viewList = getAllViewsInLine(index);
		for (View viewToRecycle : viewList) {
			removeAndRecycleView(viewToRecycle, recycler);
		}
	}

	private int getOffsetOfItemToFirstChild(int adapterPosition, RecyclerView.Recycler recycler) {
		int firstChildPosition = getChildAdapterPosition(0);
		if (firstChildPosition == adapterPosition) {
			// first child is target, just make sure it is fully visible.
			return topVisibleEdge() - getDecoratedTop(getChildAt(0));
		}

		if (adapterPosition > firstChildPosition) {
			int lastChildAdapterPosition = getChildAdapterPosition(getChildCount() - 1);
			// target child in screen, no need to calc.
			if (lastChildAdapterPosition >= adapterPosition) {
				int targetChildIndex = getChildCount() - 1 - (lastChildAdapterPosition - adapterPosition);
				return getDecoratedTop(getChildAt(targetChildIndex)) - topVisibleEdge();
			} else {
				// target child is below screen edge
				int y = getDecoratedBottom(getChildAt(getMaxHeightIndexInLine(getChildCount() - 1))) - topVisibleEdge();
				int targetAdapterPosition = lastChildAdapterPosition + 1;
				int x = layoutStartPoint().x;
				int height = 0;
				Rect rect = new Rect();
				boolean newline;
				while (targetAdapterPosition != adapterPosition) {
					View nextChild = recycler.getViewForPosition(targetAdapterPosition);
					newline = calcChildLayoutRect(nextChild, x, y, height, rect);
					if (newline) {
						x = advanceInSameLine(layoutStartPoint().x, rect);
						y = rect.top;
						height = rect.height();
					} else {
						x = advanceInSameLine(x, rect);
						height = Math.max(height, getDecoratedMeasuredHeight(nextChild));
					}
					recycler.recycleView(nextChild);
					targetAdapterPosition ++;
				}
				return y;
			}
		} else {
			// target is off screen top, Need to start from beginning in data set
			int targetAdapterPosition = 0, x = layoutStartPoint().x, height = 0;
			int y = topVisibleEdge() - getDecoratedTop(getChildAt(0));
			Rect rect = new Rect();
			boolean newline;
			while (targetAdapterPosition <= firstChildPosition) {
				View child = recycler.getViewForPosition(targetAdapterPosition);
				newline = calcChildLayoutRect(child, x, y, height, rect);
				if (newline) {
					x = advanceInSameLine(layoutStartPoint().x, rect);
					height = rect.height();
					if (targetAdapterPosition >= adapterPosition) {
						y += height;
					}
				} else {
					x = advanceInSameLine(x, rect);
					height = Math.max(height, getDecoratedMeasuredHeight(child));
				}
				targetAdapterPosition ++;
			}
			return -y;
		}
	}

	/**
	 * Is child has been marked as removed.
	 */
	private boolean isChildRemoved(View child) {
		return ((RecyclerView.LayoutParams)child.getLayoutParams()).isItemRemoved();
	}

	public FlowLayoutManager setAlignment(Alignment alignment) {
		newFlowLayoutOptions.alignment = alignment;
		return this;
	}

	public FlowLayoutManager singleItemPerLine() {
		newFlowLayoutOptions.itemsPerLine = 1;
		return this;
	}

	public FlowLayoutManager removeItemPerLineLimit() {
		newFlowLayoutOptions.itemsPerLine = FlowLayoutOptions.ITEM_PER_LINE_NO_LIMIT;
		return this;
	}

	/*****************alignment related functions*****************/
	private boolean calcChildLayoutRect(View child, int x, int y, int lineHeight, Rect rect) {
		return calcChildLayoutRect(child, x, y, lineHeight, flowLayoutOptions, rect);
	}
	private boolean calcChildLayoutRect(View child, int x, int y, int lineHeight, FlowLayoutOptions options, Rect rect) {
		boolean newLine;
		measureChildWithMargins(child, 0, 0);
		int childWidth = getDecoratedMeasuredWidth(child);
		int childHeight = getDecoratedMeasuredHeight(child);
		switch (flowLayoutOptions.alignment) {
			case RIGHT:
				if (shouldStartNewline(x, childWidth, options)) {
					newLine = true;
					rect.left = rightVisibleEdge() - childWidth;
					rect.top = y + lineHeight;
					rect.right = rightVisibleEdge();
					rect.bottom = rect.top + childHeight;
				} else {
					newLine = false;
					rect.left = x - childWidth;
					rect.top = y;
					rect.right = x;
					rect.bottom = rect.top + childHeight;
				}
				break;
			case LEFT:
			default:
				if (shouldStartNewline(x, childWidth, options)) {
					newLine = true;
					rect.left = leftVisibleEdge();
					rect.top = y + lineHeight;
					rect.right = rect.left + childWidth;
					rect.bottom = rect.top + childHeight;
				} else {
					newLine = false;
					rect.left = x;
					rect.top = y;
					rect.right = rect.left + childWidth;
					rect.bottom = rect.top + childHeight;
				}
				break;
		}

		return newLine;
	}

	private Point startNewline(Rect rect) {
		return startNewline(rect, flowLayoutOptions);

	}
	private Point startNewline(Rect rect, FlowLayoutOptions options) {
		switch (options.alignment) {
			case RIGHT:
				return new Point(rightVisibleEdge() - rect.width(), rect.top);
			case LEFT:
			default:
				return new Point(leftVisibleEdge() + rect.width(), rect.top);
		}

	}

	private int advanceInSameLine(int x, Rect rect) {
		return advanceInSameLine(x, rect, flowLayoutOptions);
	}
	private int advanceInSameLine(int x, Rect rect, FlowLayoutOptions options) {
		switch (options.alignment) {
			case RIGHT:
				return x - rect.width();
			case LEFT:
			default:
				return x + rect.width();
		}
	}

	private Point layoutStartPoint() {
		return layoutStartPoint(flowLayoutOptions);
	}
	private Point layoutStartPoint(FlowLayoutOptions options) {
		switch (options.alignment) {
			case RIGHT:
				return new Point(rightVisibleEdge(), topVisibleEdge());
			default:
				return new Point(leftVisibleEdge(), topVisibleEdge());
		}
	}

	private boolean isStartOfLine(int index) {
		return isStartOfLine(index, flowLayoutOptions);
	}
	private boolean isStartOfLine(int index, FlowLayoutOptions options) {
		if (index == 0) {
			return true;
		} else {
			if (options.itemsPerLine == 1) {
				return true;
			}
			switch (options.alignment) {
				case RIGHT:
					return getDecoratedRight(getChildAt(index)) >= rightVisibleEdge();
				case LEFT:
				default:
					return getDecoratedLeft(getChildAt(index)) <= leftVisibleEdge();
			}
		}
	}

	private boolean isEndOfLine(int index) {
		return isEndOfLine(index, flowLayoutOptions);
	}

	private boolean isEndOfLine(int index, FlowLayoutOptions options) {
		if (options.itemsPerLine == 1) {
			return true;
		}
		if (getChildCount() == 0 || index == getChildCount() - 1) {
			return true;
		}
		return isStartOfLine(index + 1, options);
	}

	private boolean shouldStartNewline(int x, int childWidth, FlowLayoutOptions options) {
		if (options.itemsPerLine == 1) {
			return true;
		}
		switch (options.alignment) {
			case RIGHT:
				return x - childWidth < leftVisibleEdge();
			case LEFT:
			default:
				return x + childWidth > rightVisibleEdge();
		}
	}
}
