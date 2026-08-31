package mobile

import "fmt"

// Reporter is implemented by Android and receives sanitized diagnostic events.
type Reporter interface {
	Report(message string)
}

func report(reporter Reporter, format string, args ...any) {
	if reporter != nil {
		reporter.Report(fmt.Sprintf(format, args...))
	}
}
