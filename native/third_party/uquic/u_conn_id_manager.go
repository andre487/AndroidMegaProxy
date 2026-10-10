package quic

// Use the limit we advertised, rather than quic-go's default of four.
func (h *connIDManager) SetConnectionIDLimit(limit uint64) {
	if limit == 0 {
		limit = 2
	}
	h.connectionIDLimit = limit
}
