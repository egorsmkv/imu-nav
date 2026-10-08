//! Resource bounds shared by the server-to-client event streams.

use axum::extract::{WebSocketUpgrade, ws::Message};
use futures_util::{Sink, SinkExt};
use serde::Serialize;
use std::time::Duration;

pub(crate) const AUTH_CHECK_INTERVAL: Duration = Duration::from_secs(60);
const MAX_INPUT_BYTES: usize = 4 * 1024;
const SEND_TIMEOUT: Duration = Duration::from_secs(10);

pub(crate) fn bounded_upgrade(upgrade: WebSocketUpgrade) -> WebSocketUpgrade {
    // Neither stream consumes application messages from clients. Bound both individual frames
    // and reassembled messages; a frame limit alone would allow arbitrarily many fragments.
    upgrade
        .read_buffer_size(MAX_INPUT_BYTES)
        .max_frame_size(MAX_INPUT_BYTES)
        .max_message_size(MAX_INPUT_BYTES)
}

pub(crate) async fn send_json<S>(socket: &mut S, message: &impl Serialize) -> anyhow::Result<()>
where
    S: Sink<Message> + Unpin,
    S::Error: Into<anyhow::Error>,
{
    let message = Message::Text(serde_json::to_string(message)?.into());
    // An unresponsive peer must not prevent periodic authorization checks indefinitely.
    tokio::time::timeout(SEND_TIMEOUT, socket.send(message))
        .await?
        .map_err(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        pin::Pin,
        task::{Context, Poll},
    };

    struct StalledSink {
        flush: bool,
    }

    impl Sink<Message> for StalledSink {
        type Error = std::io::Error;

        fn poll_ready(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
            if self.flush {
                Poll::Ready(Ok(()))
            } else {
                Poll::Pending
            }
        }
        fn start_send(self: Pin<&mut Self>, _: Message) -> Result<(), Self::Error> {
            Ok(())
        }
        fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
            Poll::Pending
        }
        fn poll_close(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
            Poll::Ready(Ok(()))
        }
    }

    #[tokio::test(start_paused = true)]
    async fn stalled_readiness_and_flush_release_the_stream_at_the_send_deadline() {
        for flush in [false, true] {
            let start = tokio::time::Instant::now();
            let error = send_json(
                &mut StalledSink { flush },
                &serde_json::json!({"type":"ready"}),
            )
            .await
            .unwrap_err();
            assert!(error.is::<tokio::time::error::Elapsed>());
            assert_eq!(tokio::time::Instant::now() - start, SEND_TIMEOUT);
        }
    }
}
