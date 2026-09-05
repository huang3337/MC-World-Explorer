mod portable_paths;
mod webview_data;
mod webview_lifecycle;

pub use portable_paths::PortablePaths;
pub(crate) use webview_data::{detect_webview_overrides, PreparedWebviewData};
pub(crate) use webview_lifecycle::WebviewDataLifecycle;
