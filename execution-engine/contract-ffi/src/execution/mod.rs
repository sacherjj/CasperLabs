#[derive(PartialEq, Eq, Clone, Copy)]
pub enum Phase {
    Payment,
    Session,
    FinalizePayment,
}
