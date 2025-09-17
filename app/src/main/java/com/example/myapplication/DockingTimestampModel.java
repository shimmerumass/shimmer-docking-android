public class DockingTimestampModel {
	// Shimmer RTC (uint64, 8 bytes)
	public long shimmerRtc;
	// Android RTC (uint32, 4 bytes)
	public int androidRtc;

	public DockingTimestampModel(long shimmerRtc, int androidRtc) {
		this.shimmerRtc = shimmerRtc;
		this.androidRtc = androidRtc;
	}

	@Override
	public String toString() {
		return "DockingTimestampModel{" +
				"shimmerRtc=" + shimmerRtc +
				", androidRtc=" + androidRtc +
				'}';
	}
}
