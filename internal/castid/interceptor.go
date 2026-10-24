package castid

import (
	"context"

	"connectrpc.com/connect"
)

// Interceptor sends the id of the cast a call is made under, and puts the id a call arrives under in its context.
func Interceptor() connect.Interceptor { return interceptor{} }

type interceptor struct{}

func (interceptor) WrapUnary(next connect.UnaryFunc) connect.UnaryFunc {
	return func(ctx context.Context, req connect.AnyRequest) (connect.AnyResponse, error) {
		if req.Spec().IsClient {
			send(ctx, req.Header())
		} else {
			ctx = receive(ctx, req.Header().Get(header))
		}
		return next(ctx, req)
	}
}

func (interceptor) WrapStreamingClient(next connect.StreamingClientFunc) connect.StreamingClientFunc {
	return func(ctx context.Context, spec connect.Spec) connect.StreamingClientConn {
		conn := next(ctx, spec)
		send(ctx, conn.RequestHeader())
		return conn
	}
}

func (interceptor) WrapStreamingHandler(next connect.StreamingHandlerFunc) connect.StreamingHandlerFunc {
	return func(ctx context.Context, conn connect.StreamingHandlerConn) error {
		return next(receive(ctx, conn.RequestHeader().Get(header)), conn)
	}
}

type setter interface{ Set(key, value string) }

func send(ctx context.Context, h setter) {
	if id, ok := From(ctx); ok {
		h.Set(header, id)
	}
}

func receive(ctx context.Context, id string) context.Context {
	if id == "" {
		return ctx
	}
	return With(ctx, id)
}
